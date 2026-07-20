package org.acme.wrapper.service.impl;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.acme.security.jwt.UserContextHolder;
import org.acme.security.jwt.dto.UserDetailsJwt;
import org.acme.security.jwt.dto.UserGroupDTO;
import org.acme.wrapper.assignment.AssignmentResolver;
import org.acme.wrapper.assignment.AssignmentResult;
import org.acme.wrapper.assignment.LastAssignmentStore;
import org.acme.wrapper.decisions.DecisionConfigStore;
import org.acme.wrapper.client.dto.UsersByGroupDto;
import org.acme.wrapper.dto.ExecuteTaskRequest;
import org.acme.wrapper.dto.TaskCandidates;
import org.acme.wrapper.exception.AssignmentValidationException;
import org.acme.wrapper.exception.UnauthorizedException;
import org.acme.wrapper.exception.WorkflowEngineException;
import org.acme.wrapper.exception.WorkflowNotFoundException;
import org.acme.wrapper.graph.NextNode;
import org.acme.wrapper.graph.NextNodeType;
import org.acme.wrapper.graph.ProcessGraphService;
import org.acme.wrapper.service.ExecOutcome;
import org.acme.wrapper.service.TaskExecutionService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

@Service
public class TaskExecutionServiceImpl implements TaskExecutionService {

    private static final String DEFAULT_PHASE = "complete";

    private static final ParameterizedTypeReference<Map<String, Object>> MAP =
            new ParameterizedTypeReference<>() {
            };

    private static final ParameterizedTypeReference<List<Map<String, Object>>> LIST_OF_MAP =
            new ParameterizedTypeReference<>() {
            };

    private final RestClient engine;
    private final AssignmentResolver assignmentResolver;
    private final ProcessGraphService processGraphService;
    private final LastAssignmentStore lastAssignmentStore;
    private final DecisionConfigStore decisionConfigStore;

    public TaskExecutionServiceImpl(
            @Value("${wrapper.engine.base-url:http://${server.address:localhost}:${server.port:8080}${server.servlet.context-path:}}")
            String engineBaseUrl,
            AssignmentResolver assignmentResolver,
            ProcessGraphService processGraphService,
            LastAssignmentStore lastAssignmentStore,
            DecisionConfigStore decisionConfigStore) {
        this.engine = RestClient.builder().baseUrl(engineBaseUrl).build();
        this.assignmentResolver = assignmentResolver;
        this.processGraphService = processGraphService;
        this.lastAssignmentStore = lastAssignmentStore;
        this.decisionConfigStore = decisionConfigStore;
    }

    @Override
    public ExecOutcome executeTask(String authorization, ExecuteTaskRequest request) {
        UserDetailsJwt jwtUser = requireJwtUser();
        String workflowName = require(request.getWorkflowName(), "MISSING_WORKFLOW_NAME", "workflowName is mandatory");
        String user = jwtUser.getUsername();
        List<String> groups = groupNames(jwtUser);

        int coords = (notBlank(request.getInstanceId()) ? 1 : 0)
                + (notBlank(request.getTaskId()) ? 1 : 0);

        if (coords == 0) {
            return startWorkflow(workflowName, request, authorization);
        }
        if (coords != 2) {
            throw new AssignmentValidationException("INVALID_TASK_COORDINATES",
                    "Provide both instanceId and taskId to complete a task, or neither to start.");
        }
        return completeTaskFlow(workflowName, request, authorization, user, groups);
    }

    // ----------------------------------------------------------------- BATCH mode

    @Override
    public ExecOutcome executeTaskBatch(String authorization, List<ExecuteTaskRequest> items) {
        UserDetailsJwt jwtUser = requireJwtUser();
        String user = jwtUser.getUsername();
        List<String> groups = groupNames(jwtUser);

        if (items == null || items.isEmpty()) {
            throw new AssignmentValidationException("EMPTY_BATCH",
                    "The request array must contain at least one item.");
        }

        // All items must target the same workflow.
        String workflowName = require(items.get(0).getWorkflowName(), "MISSING_WORKFLOW_NAME", "workflowName is mandatory");
        for (ExecuteTaskRequest item : items) {
            String wf = require(item.getWorkflowName(), "MISSING_WORKFLOW_NAME", "workflowName is mandatory");
            if (!wf.equals(workflowName)) {
                throw new AssignmentValidationException("INCONSISTENT_WORKFLOW",
                        "All items must target the same workflowName.");
            }
        }

        // Mode: all-complete (instanceId+taskId on every item) or all-start (none). Mixed → 400.
        int withCoords = 0;
        for (ExecuteTaskRequest item : items) {
            boolean hasInstance = notBlank(item.getInstanceId());
            boolean hasTask = notBlank(item.getTaskId());
            if (hasInstance ^ hasTask) {
                throw new AssignmentValidationException("INVALID_TASK_COORDINATES",
                        "Each item must provide both instanceId and taskId, or neither.");
            }
            if (hasInstance) {
                withCoords++;
            }
        }
        if (withCoords != 0 && withCoords != items.size()) {
            throw new AssignmentValidationException("INVALID_BATCH_MODE",
                    "All items must be start items (no coordinates) or complete items (instanceId+taskId).");
        }

        return (withCoords == items.size())
                ? completeArray(workflowName, items, authorization, user, groups)
                : startArray(workflowName, items, authorization);
    }

    /** Complete-array: candidates-only — a CHOICE list per already-active task, labeled by name. */
    private ExecOutcome completeArray(String workflowName, List<ExecuteTaskRequest> items, String auth,
                                      String user, List<String> groups) {
        List<TaskCandidates> results = new ArrayList<>();
        for (ExecuteTaskRequest item : items) {
            String instanceId = require(item.getInstanceId(), "MISSING_INSTANCE_ID", "instanceId is mandatory");
            String taskId = require(item.getTaskId(), "MISSING_TASK_ID", "taskId is mandatory");
            String taskName = String.valueOf(
                    fetchActiveTask(workflowName, instanceId, taskId, user, groups, auth).get("taskName"));
            List<UsersByGroupDto> users = assignmentResolver.listCandidates(item, auth);
            results.add(new TaskCandidates(taskId, taskName, null, users));
        }
        return new ExecOutcome(HttpStatus.OK, results);
    }

    /**
     * Start-array: assign each parallel first-task (by name) with its own strategy/filter.
     * Any unresolved CHOICE → 200 with a per-task candidate array (no start); otherwise one
     * start of the workflow with all parallel assignees resolved → 201.
     */
    private ExecOutcome startArray(String workflowName, List<ExecuteTaskRequest> items, String auth) {
        Map<String, Object> unionVars = new LinkedHashMap<>();
        for (ExecuteTaskRequest item : items) {
            unionVars.putAll(item.getVariables());
        }
        // Configured START decision: its variable is mandatory with an allowed value
        // (normalized in unionVars, which seeds the start variables below).
        enforceDecision(workflowName, "START", unionVars);
        List<String> firstTasks = humanTaskNames(processGraphService.firstHumanTasks(workflowName, unionVars));
        Set<String> allTasks = processGraphService.humanTasks(workflowName);

        // No task may start unassigned: every first task with a dynamic assignee must be
        // covered by an item. Tasks behind a join (e.g. Reviewer D) are assigned later by
        // the last branch completer — an item for them here is allowed but not required.
        Set<String> required = new LinkedHashSet<>();
        for (String t : firstTasks) {
            boolean dynamic = !processGraphService.actorsVars(workflowName, t).isEmpty()
                    || !processGraphService.groupsVars(workflowName, t).isEmpty();
            if (dynamic) {
                required.add(t);
            }
        }

        Set<String> covered = new HashSet<>();
        for (ExecuteTaskRequest item : items) {
            if (notBlank(item.getTaskName())) {
                covered.add(item.getTaskName().trim());
            }
        }
        List<String> missing = new ArrayList<>();
        for (String t : required) {
            if (!covered.contains(t)) {
                missing.add(t);
            }
        }
        if (!missing.isEmpty()) {
            throw new AssignmentValidationException("MISSING_TASK_ITEMS",
                    "No task may start unassigned: the array must include an item for every human task "
                            + "assigned at start of '" + workflowName + "'. Missing: " + missing);
        }

        Map<String, Object> startVars = new LinkedHashMap<>(unionVars);
        List<TaskCandidates> pending = new ArrayList<>();
        for (ExecuteTaskRequest item : items) {
            String taskName = require(item.getTaskName(), "MISSING_TASK_NAME",
                    "taskName is mandatory for each start item");
            if (!allTasks.contains(taskName)) {
                throw new AssignmentValidationException("INVALID_TASK_NAME",
                        "'" + taskName + "' is not a human task of '" + workflowName
                                + "'. Expected one of " + allTasks);
            }
            pending.addAll(resolveTaskInto(workflowName, taskName, item, auth, startVars, null));
        }

        if (!pending.isEmpty()) {
            return new ExecOutcome(HttpStatus.OK, pending);
        }
        Object created = startInstance(workflowName, auth, startVars);
        String newId = createdInstanceId(created);
        if (newId != null) {
            for (ExecuteTaskRequest item : items) {
                String t = item.getTaskName().trim();
                for (String field : union(processGraphService.actorsVars(workflowName, t),
                        processGraphService.groupsVars(workflowName, t))) {
                    recordInstanceAssignment(workflowName, t, field, newId, startVars.get(field));
                }
            }
        }
        return new ExecOutcome(HttpStatus.CREATED, created);
    }

    // ---------------------------------------------------------------- START mode

    private ExecOutcome startWorkflow(String workflowName, ExecuteTaskRequest request, String auth) {
        // Configured START decision: its variable is mandatory with an allowed value.
        enforceDecision(workflowName, "START", request.getVariables());

        // Parallel-aware: a fork can activate several first human tasks at once. Only the
        // immediately-activating tasks are assigned at start; a task behind a join (e.g.
        // Reviewer D) is assigned later, by whoever completes the last branch into the join.
        List<String> firstTasks = humanTaskNames(processGraphService.firstHumanTasks(workflowName, request.getVariables()));

        // 0 or 1 first human task → single-object behavior (unchanged CHOICE shape).
        if (firstTasks.size() <= 1) {
            NextNode next = firstTasks.isEmpty()
                    ? processGraphService.firstNode(workflowName, request.getVariables())
                    : NextNode.human(firstTasks.get(0));
            return startSingleFirstTask(workflowName, request, auth, next);
        }

        // >1 parallel first tasks → the single-object form would assign every task the same
        // way (same strategy, same actors). Reject it and force the array form so each task
        // gets its own explicit assignment.
        throw new AssignmentValidationException("TASK_ITEMS_REQUIRED",
                "'" + workflowName + "' starts with " + firstTasks.size() + " parallel human tasks "
                        + firstTasks + ". Send a JSON array with one item per task "
                        + "(each with taskName, assignmentStrategy and its own assignToActors/assignToGroups).");
    }

    private ExecOutcome startSingleFirstTask(String workflowName, ExecuteTaskRequest request, String auth, NextNode next) {
        // The first task's own Actors/Groups #{...} vars are seeded as start process
        // variables, so all are settable.
        Set<String> settableActors = Set.of();
        Set<String> settableGroups = Set.of();
        Set<String> inputs = Set.of();
        if (next.type() == NextNodeType.HUMAN && next.nodeName() != null) {
            settableActors = processGraphService.actorsVars(workflowName, next.nodeName());
            settableGroups = processGraphService.groupsVars(workflowName, next.nodeName());
            inputs = fetchTaskInputSchema(workflowName, next.nodeName(), auth);
        }
        requirePresent(inputs, union(settableActors, settableGroups), request);

        Map<String, Object> variables = new LinkedHashMap<>(request.getVariables());
        ExecOutcome choice = resolveAssignment(request, next, auth, settableActors, settableGroups, variables);
        if (choice != null) {
            return choice;
        }

        Object created = startInstance(workflowName, auth, variables);
        String newId = createdInstanceId(created);
        if (newId != null && next.type() == NextNodeType.HUMAN && next.nodeName() != null) {
            for (String field : union(settableActors, settableGroups)) {
                recordInstanceAssignment(workflowName, next.nodeName(), field, newId, variables.get(field));
            }
        }
        return new ExecOutcome(HttpStatus.CREATED, created);
    }

    private static String createdInstanceId(Object created) {
        return (created instanceof Map<?, ?> map && map.get("id") != null)
                ? map.get("id").toString() : null;
    }

    private Object startInstance(String workflowName, String auth, Map<String, Object> variables) {
        try {
            var spec = engine.post().uri("/{workflowName}", workflowName).contentType(MediaType.APPLICATION_JSON);
            if (auth != null && !auth.isBlank()) {
                spec.header(HttpHeaders.AUTHORIZATION, auth);
            }
            return spec.body(variables).retrieve().body(Object.class);
        } catch (HttpClientErrorException.NotFound nf) {
            throw new WorkflowNotFoundException(workflowName);
        } catch (HttpClientErrorException ce) {
            throw new WorkflowEngineException(workflowName, ce.getStatusCode(),
                    "ENGINE_REJECTED_REQUEST", ce.getResponseBodyAsString());
        } catch (HttpServerErrorException se) {
            throw new WorkflowEngineException(workflowName, HttpStatus.BAD_GATEWAY,
                    "ENGINE_ERROR", "The workflow engine returned an error: " + se.getResponseBodyAsString());
        } catch (ResourceAccessException unreachable) {
            throw new WorkflowEngineException(workflowName, HttpStatus.SERVICE_UNAVAILABLE,
                    "ENGINE_UNREACHABLE", "Workflow engine is not reachable.");
        }
    }

    // ------------------------------------------------------------- COMPLETE mode

    private ExecOutcome completeTaskFlow(String workflowName, ExecuteTaskRequest request, String auth,
                                         String user, List<String> groups) {
        String instanceId = request.getInstanceId().trim();
        String taskId = request.getTaskId().trim();

        // Resolve the task via the usertasks subsystem. (Deliberately NOT the classic
        // /{wf}/{id}/{task}/{taskId} endpoints: those enforce the owner policy while
        // iterating ALL active work items, and 403 when other users' parallel tasks are
        // active. The usertasks API addresses the task directly.)
        Map<String, Object> task = fetchActiveTask(workflowName, instanceId, taskId, user, groups, auth);
        String usertaskId = String.valueOf(task.get("id"));
        String taskName = String.valueOf(task.get("taskName"));

        // Lifecycle-only transitions (claim, release, …) produce no outputs and don't
        // advance the flow — fire them directly, no output/assignment validation.
        String phase = (request.getPhase() == null || request.getPhase().isBlank())
                ? DEFAULT_PHASE : request.getPhase().trim();
        if (!DEFAULT_PHASE.equals(phase)) {
            Object body = completeTask(workflowName, usertaskId, phase, user, groups, auth, request.getVariables());
            return new ExecOutcome(HttpStatus.OK, body);
        }

        // Configured decision (e.g. checkerDecision ∈ {APPROVE, REJECT, SENDBACK}): the
        // variable is mandatory and its value must be allowed; matches are normalized to
        // the configured casing. Unconfigured tasks are not validated.
        enforceDecision(workflowName, taskName, request.getVariables());

        // The engine only allows complete from Reserved — auto-claim a Ready task so a
        // candidate can complete it in one call.
        Object status = task.get("status");
        String statusName = (status instanceof Map<?, ?> map && map.get("name") != null)
                ? map.get("name").toString() : null;
        if ("Ready".equalsIgnoreCase(statusName)) {
            completeTask(workflowName, usertaskId, "claim", user, groups, auth, Map.of());
        }

        // Required completion data = the task's BPMN-declared outputs.
        Set<String> outputKeys = processGraphService.taskOutputVars(workflowName, taskName);
        Map<String, Object> resultsTemplate = new LinkedHashMap<>();
        outputKeys.forEach(k -> resultsTemplate.put(k, null));

        Map<String, Object> mergedVars = new LinkedHashMap<>(fetchProcessVariables(workflowName, instanceId, auth));
        mergedVars.putAll(request.getVariables());

        // A parallel fork (or join) after this task: the fan-out sees the human tasks the
        // single-path lookahead reports as UNKNOWN — every one of them must end up assigned.
        List<String> nextTasks = humanTaskNames(processGraphService.nextHumanTasksAfter(workflowName, taskName, mergedVars));
        NextNode next = processGraphService.nextAfterTask(workflowName, taskName, mergedVars);
        if (nextTasks.size() > 1 || (!nextTasks.isEmpty() && next.type() == NextNodeType.UNKNOWN)) {
            return completeIntoParallel(workflowName, instanceId, taskName, usertaskId, request, auth,
                    user, groups, resultsTemplate, mergedVars, nextTasks);
        }

        // The next human task's Actors/Groups vars split into: settable via this task's
        // outputs (written in the completion body) vs. process-variable-only (e.g. a
        // sendback loop re-activating an earlier task) — those must be re-assigned
        // explicitly and patched into the instance before completing.
        Set<String> settableActors = Set.of();
        Set<String> settableGroups = Set.of();
        Set<String> reassignActors = Set.of();
        Set<String> reassignGroups = Set.of();
        if (next.type() == NextNodeType.HUMAN && next.nodeName() != null) {
            Set<String> actorVars = processGraphService.actorsVars(workflowName, next.nodeName());
            Set<String> groupVars = processGraphService.groupsVars(workflowName, next.nodeName());
            settableActors = intersect(actorVars, resultsTemplate.keySet());
            settableGroups = intersect(groupVars, resultsTemplate.keySet());
            reassignActors = minus(actorVars, resultsTemplate.keySet());
            reassignGroups = minus(groupVars, resultsTemplate.keySet());
        }
        requirePresent(resultsTemplate.keySet(), union(settableActors, settableGroups), request);

        Map<String, Object> accumulated = new LinkedHashMap<>(request.getVariables());
        ExecOutcome choice = resolveAssignment(request, next, auth, settableActors, settableGroups, accumulated);
        if (choice != null) {
            return choice;
        }

        // No task may re-activate silently with a stale assignee: resolve the
        // non-output vars with the caller's strategy and patch them in first.
        if (!reassignActors.isEmpty() || !reassignGroups.isEmpty()) {
            Map<String, Object> reassigned = new LinkedHashMap<>();
            List<TaskCandidates> pending =
                    resolveVars(next.nodeName(), reassignActors, reassignGroups, request, auth, reassigned);
            if (!pending.isEmpty()) {
                return new ExecOutcome(HttpStatus.OK, pending);
            }
            if (!reassigned.isEmpty()) {
                patchProcessVariables(workflowName, instanceId, auth, reassigned);
            }
        }

        Map<String, Object> output = pick(resultsTemplate.keySet(), accumulated);
        Object body = completeTask(workflowName, usertaskId, DEFAULT_PHASE, user, groups, auth, output);
        return new ExecOutcome(HttpStatus.OK, body);
    }

    private static Set<String> minus(Set<String> a, Set<String> b) {
        Set<String> out = new LinkedHashSet<>(a);
        out.removeAll(b);
        return out;
    }

    /**
     * Complete a task whose next node(s) lie across parallel gateways. Assignee vars that are
     * outputs of this task are resolved as usual. Vars no output can set (a task behind the
     * JOIN, e.g. Reviewer D) are assigned by whoever completes the LAST active branch: earlier
     * completers are not asked; the last one must pass assignmentStrategy (else 400), and the
     * resolved values are patched into the process variables before the completion fires the
     * join — so the task activates already assigned.
     */
    private ExecOutcome completeIntoParallel(String workflowName, String instanceId, String taskName, String usertaskId,
                                             ExecuteTaskRequest request, String auth, String user, List<String> groups,
                                             Map<String, Object> resultsTemplate, Map<String, Object> mergedVars,
                                             List<String> nextTasks) {
        Set<String> exempt = new LinkedHashSet<>();
        Map<String, Set<String>> lateActorsByTask = new LinkedHashMap<>();
        Map<String, Set<String>> lateGroupsByTask = new LinkedHashMap<>();
        for (String t : nextTasks) {
            Set<String> actorVars = processGraphService.actorsVars(workflowName, t);
            Set<String> groupVars = processGraphService.groupsVars(workflowName, t);
            exempt.addAll(intersect(actorVars, resultsTemplate.keySet()));
            exempt.addAll(intersect(groupVars, resultsTemplate.keySet()));
            Set<String> lateActors = lateVarsOf(actorVars, resultsTemplate, mergedVars);
            Set<String> lateGroups = lateVarsOf(groupVars, resultsTemplate, mergedVars);
            if (!lateActors.isEmpty() || !lateGroups.isEmpty()) {
                lateActorsByTask.put(t, lateActors);
                lateGroupsByTask.put(t, lateGroups);
            }
        }
        requirePresent(resultsTemplate.keySet(), exempt, request);

        Map<String, Object> accumulated = new LinkedHashMap<>(request.getVariables());
        List<TaskCandidates> pending = new ArrayList<>();
        for (String t : nextTasks) {
            pending.addAll(resolveTaskInto(workflowName, t, request, auth, accumulated, resultsTemplate.keySet()));
        }

        // Only the completion that fires the join (no other branch task still active) must
        // assign the late tasks; everyone before completes without being asked.
        Map<String, Object> lateVars = new LinkedHashMap<>();
        if (!lateActorsByTask.isEmpty() && isLastActiveHumanTask(workflowName, instanceId, taskName, auth)) {
            for (Map.Entry<String, Set<String>> e : lateActorsByTask.entrySet()) {
                String t = e.getKey();
                pending.addAll(resolveVars(t, e.getValue(),
                        lateGroupsByTask.getOrDefault(t, Set.of()), request, auth, lateVars));
            }
        }
        if (!pending.isEmpty()) {
            return new ExecOutcome(HttpStatus.OK, pending);
        }
        if (!lateVars.isEmpty()) {
            patchProcessVariables(workflowName, instanceId, auth, lateVars);
        }

        Map<String, Object> output = pick(resultsTemplate.keySet(), accumulated);
        Object body = completeTask(workflowName, usertaskId, DEFAULT_PHASE, user, groups, auth, output);
        return new ExecOutcome(HttpStatus.OK, body);
    }

    // ------------------------------------------------------------------ rollback

    @Override
    public ExecOutcome rollback(String authorization, ExecuteTaskRequest request) {
        requireJwtUser();
        String workflowName = require(request.getWorkflowName(), "MISSING_WORKFLOW_NAME", "workflowName is mandatory");
        String instanceId = require(request.getInstanceId(), "MISSING_INSTANCE_ID", "instanceId is mandatory");

        // Target task: explicit, or the instance's last completed human task.
        String target = request.getTaskName();
        if (target == null || target.isBlank()) {
            target = lastCompletedHumanTask(instanceId, authorization);
            if (target == null) {
                // Distinguish "instance unknown" (404) from "nothing completed yet" (400).
                if (fetchNodeInstances(workflowName, instanceId, authorization) == null
                        && fetchEndedInstanceVariables(workflowName, instanceId, authorization) == null) {
                    throw new WorkflowEngineException(workflowName, HttpStatus.NOT_FOUND, "INSTANCE_NOT_FOUND",
                            "Instance '" + instanceId + "' was not found, live or in history.");
                }
                throw new AssignmentValidationException("NOTHING_TO_ROLLBACK",
                        "No completed human task found for instance '" + instanceId + "'.");
            }
        } else {
            target = target.trim();
        }
        String nodeId = processGraphService.taskNodeId(workflowName, target);
        if (nodeId == null) {
            throw new AssignmentValidationException("INVALID_TASK_NAME",
                    "'" + target + "' is not a human task of '" + workflowName + "'. Expected one of "
                            + processGraphService.humanTasks(workflowName));
        }

        List<Map<String, Object>> active = fetchNodeInstances(workflowName, instanceId, authorization);
        if (active != null) {
            // Live instance: bring the target back first, then cancel what was active —
            // the instance never sits with zero active nodes.
            triggerNode(workflowName, instanceId, nodeId, authorization);
            for (Map<String, Object> node : active) {
                Object nid = nodeInstanceId(node);
                if (nid != null) {
                    cancelNodeInstance(workflowName, instanceId, nid.toString(), authorization);
                }
            }
            return rollbackOutcome(workflowName, target, instanceId, null, authorization);
        }

        // Ended instance: re-create it from the data-index's final variables, then steer
        // the new instance to the target task.
        Map<String, Object> variables = fetchEndedInstanceVariables(workflowName, instanceId, authorization);
        if (variables == null) {
            throw new WorkflowEngineException(workflowName, HttpStatus.NOT_FOUND, "INSTANCE_NOT_FOUND",
                    "Instance '" + instanceId + "' was not found, live or in history.");
        }
        variables.remove("id");
        Object created = startInstance(workflowName, authorization, variables);
        String newId = (created instanceof Map<?, ?> map && map.get("id") != null)
                ? map.get("id").toString() : null;
        if (newId == null) {
            throw new WorkflowEngineException(workflowName, HttpStatus.BAD_GATEWAY,
                    "ENGINE_ERROR", "Could not determine the id of the re-created instance.");
        }

        List<Map<String, Object>> autoActive = fetchNodeInstances(workflowName, newId, authorization);
        boolean targetAlreadyActive = false;
        if (autoActive != null) {
            for (Map<String, Object> node : autoActive) {
                targetAlreadyActive |= target.equals(String.valueOf(nodeName(node)));
            }
        }
        if (!targetAlreadyActive) {
            triggerNode(workflowName, newId, nodeId, authorization);
            if (autoActive != null) {
                for (Map<String, Object> node : autoActive) {
                    Object nid = nodeInstanceId(node);
                    if (nid != null) {
                        cancelNodeInstance(workflowName, newId, nid.toString(), authorization);
                    }
                }
            }
        }
        return rollbackOutcome(workflowName, target, newId, instanceId, authorization);
    }

    private ExecOutcome rollbackOutcome(String workflowName, String target, String instanceId,
                                        String previousInstanceId, String auth) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("rolledBackTo", target);
        body.put("instanceId", instanceId);
        if (previousInstanceId != null) {
            body.put("previousInstanceId", previousInstanceId);
        }
        body.put("activeNodes", fetchActiveNodeNames(workflowName, instanceId, auth));
        return new ExecOutcome(HttpStatus.OK, body);
    }

    // ------------------------------------------------------------ instance status

    private static final String INSTANCE_STATUS_QUERY = """
            query ($where: ProcessInstanceArgument) {
              ProcessInstances(where: $where) {
                id processId processName state start end businessKey
              }
            }
            """;

    private static final String INSTANCE_TASKS_QUERY = """
            query ($where: UserTaskInstanceArgument) {
              UserTaskInstances(where: $where, orderBy: { started: ASC }) {
                id externalReferenceId name state actualOwner
                potentialUsers potentialGroups started completed lastUpdate
              }
            }
            """;

    @Override
    @SuppressWarnings("unchecked")
    public ExecOutcome instanceStatus(String authorization, String instanceId) {
        requireJwtUser();
        String id = require(instanceId, "MISSING_INSTANCE_ID", "instanceId is mandatory");

        Map<String, Object> response = dataIndexQuery(authorization, INSTANCE_STATUS_QUERY,
                Map.of("where", Map.of("id", Map.of("equal", id))));
        Map<String, Object> instance = null;
        if (response != null && response.get("data") instanceof Map<?, ?> data
                && data.get("ProcessInstances") instanceof List<?> list && !list.isEmpty()
                && list.get(0) instanceof Map<?, ?> first) {
            instance = (Map<String, Object>) first;
        }
        if (instance == null) {
            throw new WorkflowEngineException(null, HttpStatus.NOT_FOUND, "INSTANCE_NOT_FOUND",
                    "Instance '" + id + "' was not found.");
        }

        Map<String, Object> tasksResponse = dataIndexQuery(authorization, INSTANCE_TASKS_QUERY,
                Map.of("where", Map.of("processInstanceId", Map.of("equal", id))));
        List<Map<String, Object>> tasks = List.of();
        if (tasksResponse != null && tasksResponse.get("data") instanceof Map<?, ?> data
                && data.get("UserTaskInstances") instanceof List<?> list) {
            tasks = (List<Map<String, Object>>) list;
        }

        List<Map<String, Object>> activeTasks = new ArrayList<>();
        List<Map<String, Object>> completedTasks = new ArrayList<>();
        for (Map<String, Object> task : tasks) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("taskId", task.get("externalReferenceId") != null
                    ? task.get("externalReferenceId") : task.get("id"));
            entry.put("taskName", task.get("name"));
            entry.put("state", task.get("state"));
            entry.put("actualOwner", task.get("actualOwner"));
            entry.put("potentialUsers", task.get("potentialUsers"));
            entry.put("potentialGroups", task.get("potentialGroups"));
            entry.put("started", task.get("started"));
            entry.put("completed", task.get("completed"));
            String state = task.get("state") == null ? "" : task.get("state").toString();
            if ("Completed".equalsIgnoreCase(state) || "Aborted".equalsIgnoreCase(state)) {
                completedTasks.add(entry);
            } else {
                activeTasks.add(entry);
            }
        }

        String state = instance.get("state") == null ? null : instance.get("state").toString();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("instanceId", instance.get("id"));
        body.put("workflowName", instance.get("processId"));
        body.put("workflowLabel", instance.get("processName"));
        body.put("status", state);
        body.put("completed", state != null && !"ACTIVE".equalsIgnoreCase(state));
        body.put("businessKey", instance.get("businessKey"));
        body.put("start", instance.get("start"));
        body.put("end", instance.get("end"));
        body.put("activeTasks", activeTasks);
        body.put("completedTasks", completedTasks);
        return new ExecOutcome(HttpStatus.OK, body);
    }

    /** The vars this task's outputs cannot set and that hold no value yet. */
    private Set<String> lateVarsOf(Set<String> vars, Map<String, Object> resultsTemplate,
                                   Map<String, Object> mergedVars) {
        Set<String> late = new LinkedHashSet<>();
        for (String v : vars) {
            if (!resultsTemplate.containsKey(v) && isBlankValue(mergedVars.get(v))) {
                late.add(v);
            }
        }
        return late;
    }

    /** True when {@code taskName} is the only human task still active in the instance. */
    private boolean isLastActiveHumanTask(String workflowName, String instanceId, String taskName, String auth) {
        Set<String> active = fetchActiveNodeNames(workflowName, instanceId, auth);
        if (active == null) {
            return true; // can't tell → safe side: demand the assignment now
        }
        Set<String> humans = processGraphService.humanTasks(workflowName);
        for (String name : active) {
            if (humans.contains(name) && !name.equals(taskName)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isBlankValue(Object v) {
        return v == null || v.toString().isBlank();
    }

    // --------------------------------------------------------------- assignment

    /**
     * Apply assignment into {@code out}: Groups vars are set directly from
     * {@code assignToGroups}; Actors vars are resolved by {@code assignmentStrategy}
     * (seeded from {@code assignToActors} for USER/GROUP/CHOICE). Returns a non-null
     * {@link ExecOutcome} (200 candidate list) if a CHOICE strategy has no selection.
     */
    private ExecOutcome resolveAssignment(ExecuteTaskRequest request, NextNode next, String auth,
                                          Set<String> settableActors, Set<String> settableGroups,
                                          Map<String, Object> out) {
        // Groups: placed directly (no strategy), only if the caller supplied them.
        applyGroups(request, settableGroups, out);

        // Actors: resolved by the strategy.
        if (!settableActors.isEmpty()) {
            requireStrategy(request, next.nodeName());
            seedActors(request, settableActors);
            for (String a : settableActors) {
                AssignmentResult result = resolveField(request, next.nodeName(), a, auth);
                if (result instanceof AssignmentResult.Choices choices) {
                    return new ExecOutcome(HttpStatus.OK, choices.users());
                }
                out.put(a, ((AssignmentResult.Resolved) result).variables().get(a));
            }
        }
        return null;
    }

    /**
     * Resolve one (parallel) task's Actors/Groups vars into {@code out}, using {@code item}'s
     * own strategy/filter. {@code limitTo} (non-null in complete mode) restricts the settable
     * fields to the completing task's outputs. Returns any {@link TaskCandidates} produced by
     * an unresolved CHOICE (one per such field); an empty list means everything resolved.
     */
    private List<TaskCandidates> resolveTaskInto(String workflowName, String taskName,
                                                 ExecuteTaskRequest item, String auth, Map<String, Object> out,
                                                 Set<String> limitTo) {
        Set<String> settableActors = processGraphService.actorsVars(workflowName, taskName);
        Set<String> settableGroups = processGraphService.groupsVars(workflowName, taskName);
        if (limitTo != null) {
            settableActors = intersect(settableActors, limitTo);
            settableGroups = intersect(settableGroups, limitTo);
        }
        return resolveVars(taskName, settableActors, settableGroups, item, auth, out);
    }

    /** Resolve explicit actor/group var sets of {@code taskName} into {@code out}. */
    private List<TaskCandidates> resolveVars(String taskName, Set<String> settableActors, Set<String> settableGroups,
                                             ExecuteTaskRequest item, String auth, Map<String, Object> out) {
        applyGroups(item, settableGroups, out);

        List<TaskCandidates> pending = new ArrayList<>();
        if (!settableActors.isEmpty()) {
            requireStrategy(item, taskName);
            seedActors(item, settableActors);
            for (String field : settableActors) {
                AssignmentResult result = resolveField(item, taskName, field, auth);
                if (result instanceof AssignmentResult.Choices choices) {
                    pending.add(new TaskCandidates(null, taskName, field, choices.users()));
                } else {
                    out.put(field, ((AssignmentResult.Resolved) result).variables().get(field));
                }
            }
        }
        return pending;
    }

    /** Apply the caller-supplied Groups selection directly into the group vars. */
    private void applyGroups(ExecuteTaskRequest request, Set<String> settableGroups, Map<String, Object> out) {
        if (!settableGroups.isEmpty() && notEmpty(request.getAssignToGroups())) {
            String groupsValue = String.join(",", request.getAssignToGroups());
            for (String g : settableGroups) {
                out.put(g, groupsValue);
            }
        }
    }

    /** Seed the caller-supplied Actors selection into the actor vars (for USER/GROUP/CHOICE). */
    private void seedActors(ExecuteTaskRequest request, Set<String> settableActors) {
        if (notEmpty(request.getAssignToActors())) {
            String actorsValue = String.join(",", request.getAssignToActors());
            for (String a : settableActors) {
                request.getVariables().put(a, actorsValue);
            }
        }
    }

    /** Point the request at one field of one task and resolve it via the strategy. */
    private AssignmentResult resolveField(ExecuteTaskRequest request, String taskName, String field, String auth) {
        request.setAssigneToFieldName(field);
        String instanceId = notBlank(request.getInstanceId()) ? request.getInstanceId().trim() : null;
        AssignmentResult result = assignmentResolver.resolve(request, auth, taskName, instanceId);
        // Record who holds this task in this instance (any strategy) so in-instance
        // LAST_ASSIGN_TO_* can reuse it later, e.g. on sendback.
        if (instanceId != null && result instanceof AssignmentResult.Resolved resolved) {
            recordInstanceAssignment(request.getWorkflowName(), taskName, field, instanceId,
                    resolved.variables().get(field));
        }
        return result;
    }

    /**
     * When a decision is configured for (workflow, task/START): require the configured
     * variable in {@code variables}, validate its value case-insensitively against the
     * allowed values, and normalize it to the configured canonical casing. 400 otherwise.
     */
    @SuppressWarnings("unchecked")
    private void enforceDecision(String workflowName, String taskName, Map<String, Object> variables) {
        Map<String, Object> config = decisionConfigStore.find(workflowName, taskName).orElse(null);
        if (config == null) {
            return;
        }
        String variable = String.valueOf(config.get("variableName"));
        List<String> allowed = (List<String>) config.get("allowedValues");
        String stage = "START".equals(taskName)
                ? "starting '" + workflowName + "'"
                : "completing '" + taskName + "' of '" + workflowName + "'";

        Object value = variables.get(variable);
        if (value == null || value.toString().isBlank()) {
            throw new AssignmentValidationException("MISSING_DECISION",
                    "'" + variable + "' is mandatory when " + stage + "; allowed values: " + allowed);
        }
        String submitted = value.toString().trim();
        String canonical = allowed.stream()
                .filter(a -> a.equalsIgnoreCase(submitted))
                .findFirst()
                .orElseThrow(() -> new AssignmentValidationException("INVALID_DECISION",
                        "'" + submitted + "' is not a valid value for '" + variable + "' when "
                                + stage + "; allowed values: " + allowed));
        variables.put(variable, canonical);
    }

    /** Persist "this user has this task in this instance" (no-op for blank values). */
    private void recordInstanceAssignment(String workflowName, String taskName, String field,
                                          String instanceId, Object value) {
        if (value != null && !value.toString().isBlank()) {
            UserDetailsJwt user = UserContextHolder.getContext();
            lastAssignmentStore.upsert(workflowName, taskName, field, instanceId,
                    value.toString(), (user != null) ? user.getUsername() : null);
        }
    }

    private static List<String> humanTaskNames(List<NextNode> nodes) {
        List<String> names = new ArrayList<>();
        for (NextNode n : nodes) {
            if (n.type() == NextNodeType.HUMAN && n.nodeName() != null) {
                names.add(n.nodeName());
            }
        }
        return names;
    }

    private Set<String> union(Set<String> a, Set<String> b) {
        Set<String> out = new LinkedHashSet<>(a);
        out.addAll(b);
        return out;
    }

    private static boolean notEmpty(List<String> list) {
        return list != null && !list.isEmpty();
    }

    private void requireStrategy(ExecuteTaskRequest request, String taskName) {
        if (request.getAssignmentStrategy() == null || request.getAssignmentStrategy().isBlank()) {
            String who = (taskName != null) ? "'" + taskName + "'" : "the next task";
            throw new AssignmentValidationException("ASSIGNMENT_REQUIRED",
                    "Task " + who + " has a dynamic assignee; assignmentStrategy is required.");
        }
    }

    /** Every key (excluding {@code exempt}) must be present in the request. */
    private void requirePresent(Set<String> keys, Set<String> exempt, ExecuteTaskRequest request) {
        List<String> missing = new ArrayList<>();
        for (String key : keys) {
            if ((exempt == null || !exempt.contains(key)) && !request.getVariables().containsKey(key)) {
                missing.add(key);
            }
        }
        if (!missing.isEmpty()) {
            throw new AssignmentValidationException("MISSING_TASK_VARIABLES",
                    "Missing required task variables: " + missing);
        }
    }

    private Map<String, Object> pick(Set<String> keys, Map<String, Object> source) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String key : keys) {
            out.put(key, source.get(key));
        }
        return out;
    }

    private Set<String> intersect(Set<String> a, Set<String> b) {
        Set<String> out = new LinkedHashSet<>();
        for (String s : a) {
            if (b.contains(s)) {
                out.add(s);
            }
        }
        return out;
    }

    // --------------------------------------------------------------- engine I/O

    /**
     * Find the active task by its work-item id. Lists the instance's active tasks
     * (GET {@code /{workflowName}/{instanceId}/tasks?user=&group=}) and returns the entry
     * whose {@code id} equals {@code taskId} — including its {@code name} and {@code results}.
     * This lets callers pass only {@code instanceId}/{@code taskId} without the node name.
     */
    private Map<String, Object> fetchActiveTask(String workflowName, String instanceId, String taskId,
                                                String user, List<String> groups, String auth) {
        List<Map<String, Object>> tasks;
        try {
            var spec = engine.get().uri(uri -> {
                uri.path("/usertasks/instance").queryParam("user", user);
                groups.forEach(g -> uri.queryParam("group", g));
                return uri.build();
            });
            if (auth != null && !auth.isBlank()) {
                spec.header(HttpHeaders.AUTHORIZATION, auth);
            }
            tasks = spec.retrieve().body(LIST_OF_MAP);
        } catch (HttpClientErrorException.NotFound nf) {
            throw new WorkflowEngineException(workflowName, HttpStatus.NOT_FOUND,
                    "TASK_NOT_FOUND", "No active tasks found for the given user");
        } catch (ResourceAccessException unreachable) {
            throw new WorkflowEngineException(workflowName, HttpStatus.SERVICE_UNAVAILABLE,
                    "ENGINE_UNREACHABLE", "Workflow engine is not reachable.");
        }

        if (tasks != null) {
            for (Map<String, Object> task : tasks) {
                // Callers may hold either the usertask id or the work-item id (externalReferenceId).
                boolean idMatch = taskId.equals(String.valueOf(task.get("id")))
                        || taskId.equals(String.valueOf(task.get("externalReferenceId")));
                if (idMatch && instanceId.equals(processInstanceIdOf(task)) && task.get("taskName") != null) {
                    return task;
                }
            }
        }
        throw new WorkflowEngineException(workflowName, HttpStatus.NOT_FOUND,
                "TASK_NOT_FOUND", "Task not found for taskId '" + taskId
                        + "' among the active tasks of instance '" + instanceId + "'");
    }

    private static String processInstanceIdOf(Map<String, Object> task) {
        Object info = task.get("processInfo");
        if (info instanceof Map<?, ?> map) {
            return String.valueOf(map.get("processInstanceId"));
        }
        return null;
    }

    /** Task name as it appears in the engine's REST paths (Kogito turns spaces into underscores). */
    private static String pathTaskName(String taskName) {
        return taskName.trim().replaceAll("\\s+", "_");
    }

    /**
     * Names of the instance's active node instances (process-management addon,
     * {@code GET /management/processes/{pid}/instances/{iid}/nodeInstances}).
     * Returns {@code null} when the addon/endpoint is unavailable.
     */
    private Set<String> fetchActiveNodeNames(String workflowName, String instanceId, String auth) {
        List<Map<String, Object>> nodes = fetchNodeInstances(workflowName, instanceId, auth);
        if (nodes == null) {
            return null;
        }
        Set<String> names = new LinkedHashSet<>();
        for (Map<String, Object> node : nodes) {
            Object name = nodeName(node);
            if (name != null) {
                names.add(name.toString());
            }
        }
        return names;
    }

    /**
     * The instance's active node instances (raw maps with id + name), or {@code null} when
     * the instance is unknown to the engine / the management addon is unavailable.
     */
    private List<Map<String, Object>> fetchNodeInstances(String workflowName, String instanceId, String auth) {
        try {
            var spec = engine.get().uri(
                    "/management/processes/{processId}/instances/{processInstanceId}/nodeInstances",
                    workflowName, instanceId);
            if (auth != null && !auth.isBlank()) {
                spec.header(HttpHeaders.AUTHORIZATION, auth);
            }
            return spec.retrieve().body(LIST_OF_MAP);
        } catch (HttpClientErrorException | HttpServerErrorException e) {
            return null;
        } catch (ResourceAccessException unreachable) {
            throw new WorkflowEngineException(workflowName, HttpStatus.SERVICE_UNAVAILABLE,
                    "ENGINE_UNREACHABLE", "Workflow engine is not reachable.");
        }
    }

    private static Object nodeName(Map<String, Object> node) {
        return node.getOrDefault("name", node.get("nodeName"));
    }

    private static Object nodeInstanceId(Map<String, Object> node) {
        return node.getOrDefault("nodeInstanceId", node.get("id"));
    }

    /** Trigger a node (by BPMN element id) on a live instance (process-management addon). */
    private void triggerNode(String workflowName, String instanceId, String nodeId, String auth) {
        try {
            var spec = engine.post().uri(
                    "/management/processes/{processId}/instances/{processInstanceId}/nodes/{nodeId}",
                    workflowName, instanceId, nodeId);
            if (auth != null && !auth.isBlank()) {
                spec.header(HttpHeaders.AUTHORIZATION, auth);
            }
            spec.retrieve().toBodilessEntity();
        } catch (HttpClientErrorException ce) {
            throw new WorkflowEngineException(workflowName, ce.getStatusCode(),
                    "ROLLBACK_TRIGGER_FAILED", "Could not trigger node '" + nodeId + "': " + ce.getResponseBodyAsString());
        } catch (HttpServerErrorException se) {
            throw new WorkflowEngineException(workflowName, HttpStatus.BAD_GATEWAY,
                    "ENGINE_ERROR", "The workflow engine returned an error: " + se.getResponseBodyAsString());
        } catch (ResourceAccessException unreachable) {
            throw new WorkflowEngineException(workflowName, HttpStatus.SERVICE_UNAVAILABLE,
                    "ENGINE_UNREACHABLE", "Workflow engine is not reachable.");
        }
    }

    /** Cancel one active node instance (process-management addon). */
    private void cancelNodeInstance(String workflowName, String instanceId, String nodeInstanceId, String auth) {
        try {
            var spec = engine.delete().uri(
                    "/management/processes/{processId}/instances/{processInstanceId}/nodeInstances/{nodeInstanceId}",
                    workflowName, instanceId, nodeInstanceId);
            if (auth != null && !auth.isBlank()) {
                spec.header(HttpHeaders.AUTHORIZATION, auth);
            }
            spec.retrieve().toBodilessEntity();
        } catch (HttpClientErrorException ce) {
            throw new WorkflowEngineException(workflowName, ce.getStatusCode(),
                    "ROLLBACK_CANCEL_FAILED", "Could not cancel node instance '" + nodeInstanceId + "': "
                            + ce.getResponseBodyAsString());
        } catch (HttpServerErrorException se) {
            throw new WorkflowEngineException(workflowName, HttpStatus.BAD_GATEWAY,
                    "ENGINE_ERROR", "The workflow engine returned an error: " + se.getResponseBodyAsString());
        } catch (ResourceAccessException unreachable) {
            throw new WorkflowEngineException(workflowName, HttpStatus.SERVICE_UNAVAILABLE,
                    "ENGINE_UNREACHABLE", "Workflow engine is not reachable.");
        }
    }

    private static final String LAST_COMPLETED_TASK_QUERY = """
            query ($where: UserTaskInstanceArgument) {
              UserTaskInstances(where: $where, orderBy: { completed: DESC },
                                pagination: { limit: 1, offset: 0 }) {
                name
              }
            }
            """;

    /** Name of the instance's most recently completed human task (data-index), or null. */
    private String lastCompletedHumanTask(String instanceId, String auth) {
        Map<String, Object> where = Map.of(
                "processInstanceId", Map.of("equal", instanceId),
                "state", Map.of("equal", "Completed"));
        Map<String, Object> response = dataIndexQuery(auth, LAST_COMPLETED_TASK_QUERY, Map.of("where", where));
        if (response != null && response.get("data") instanceof Map<?, ?> data
                && data.get("UserTaskInstances") instanceof List<?> list && !list.isEmpty()
                && list.get(0) instanceof Map<?, ?> task && task.get("name") != null) {
            return task.get("name").toString();
        }
        return null;
    }

    private static final String ENDED_INSTANCE_QUERY = """
            query ($where: ProcessInstanceArgument) {
              ProcessInstances(where: $where) { id state variables }
            }
            """;

    /**
     * Final variables of an ended instance from the data-index, or null when the instance
     * is unknown there too.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> fetchEndedInstanceVariables(String workflowName, String instanceId, String auth) {
        Map<String, Object> where = Map.of(
                "id", Map.of("equal", instanceId),
                "processId", Map.of("equal", workflowName));
        Map<String, Object> response = dataIndexQuery(auth, ENDED_INSTANCE_QUERY, Map.of("where", where));
        if (response != null && response.get("data") instanceof Map<?, ?> data
                && data.get("ProcessInstances") instanceof List<?> list && !list.isEmpty()
                && list.get(0) instanceof Map<?, ?> instance) {
            Object variables = instance.get("variables");
            if (variables instanceof Map<?, ?> map) {
                return new LinkedHashMap<>((Map<String, Object>) map);
            }
            return new LinkedHashMap<>();
        }
        return null;
    }

    /** POST a query+variables to the data-index {@code /graphql}; GraphQL errors → 502. */
    private Map<String, Object> dataIndexQuery(String auth, String query, Map<String, Object> variables) {
        Map<String, Object> response;
        try {
            var spec = engine.post().uri("/graphql").contentType(MediaType.APPLICATION_JSON);
            if (auth != null && !auth.isBlank()) {
                spec.header(HttpHeaders.AUTHORIZATION, auth);
            }
            response = spec.body(Map.of("query", query, "variables", variables))
                    .retrieve().body(MAP);
        } catch (ResourceAccessException unreachable) {
            throw new WorkflowEngineException(null, HttpStatus.SERVICE_UNAVAILABLE,
                    "ENGINE_UNREACHABLE", "Workflow engine is not reachable.");
        }
        if (response != null && response.get("errors") != null) {
            throw new WorkflowEngineException(null, HttpStatus.BAD_GATEWAY,
                    "ENGINE_ERROR", "Data-index query failed: " + response.get("errors"));
        }
        return response;
    }

    /** Set process variables on a live instance (partial model update, PATCH). */
    private void patchProcessVariables(String workflowName, String instanceId, String auth,
                                       Map<String, Object> vars) {
        try {
            var spec = engine.patch().uri("/{workflowName}/{instanceId}", workflowName, instanceId)
                    .contentType(MediaType.APPLICATION_JSON);
            if (auth != null && !auth.isBlank()) {
                spec.header(HttpHeaders.AUTHORIZATION, auth);
            }
            spec.body(vars).retrieve().toBodilessEntity();
        } catch (HttpClientErrorException ce) {
            throw new WorkflowEngineException(workflowName, ce.getStatusCode(),
                    "ENGINE_REJECTED_REQUEST", ce.getResponseBodyAsString());
        } catch (HttpServerErrorException se) {
            throw new WorkflowEngineException(workflowName, HttpStatus.BAD_GATEWAY,
                    "ENGINE_ERROR", "The workflow engine returned an error: " + se.getResponseBodyAsString());
        } catch (ResourceAccessException unreachable) {
            throw new WorkflowEngineException(workflowName, HttpStatus.SERVICE_UNAVAILABLE,
                    "ENGINE_UNREACHABLE", "Workflow engine is not reachable.");
        }
    }

    /** Current process variables (flat map) used to evaluate gateway conditions. */
    private Map<String, Object> fetchProcessVariables(String workflowName, String instanceId, String auth) {
        try {
            var spec = engine.get().uri("/{workflowName}/{instanceId}", workflowName, instanceId);
            if (auth != null && !auth.isBlank()) {
                spec.header(HttpHeaders.AUTHORIZATION, auth);
            }
            Map<String, Object> vars = spec.retrieve().body(MAP);
            return (vars == null) ? Map.of() : vars;
        } catch (HttpClientErrorException nf) {
            return Map.of();
        } catch (ResourceAccessException unreachable) {
            throw new WorkflowEngineException(workflowName, HttpStatus.SERVICE_UNAVAILABLE,
                    "ENGINE_UNREACHABLE", "Workflow engine is not reachable.");
        }
    }

    /** The declared input variable names of a task (from its JSON schema). */
    @SuppressWarnings("unchecked")
    private Set<String> fetchTaskInputSchema(String workflowName, String taskName, String auth) {
        try {
            var spec = engine.get().uri("/{workflowName}/{taskName}/schema", workflowName, pathTaskName(taskName));
            if (auth != null && !auth.isBlank()) {
                spec.header(HttpHeaders.AUTHORIZATION, auth);
            }
            Map<String, Object> schema = spec.retrieve().body(MAP);
            Set<String> inputs = new LinkedHashSet<>();
            if (schema != null && schema.get("properties") instanceof Map<?, ?> props) {
                for (Map.Entry<String, Object> e : ((Map<String, Object>) props).entrySet()) {
                    if (e.getValue() instanceof Map<?, ?> meta && Boolean.TRUE.equals(meta.get("input"))) {
                        inputs.add(e.getKey());
                    }
                }
            }
            return inputs;
        } catch (HttpClientErrorException nf) {
            return Set.of();
        } catch (ResourceAccessException unreachable) {
            throw new WorkflowEngineException(workflowName, HttpStatus.SERVICE_UNAVAILABLE,
                    "ENGINE_UNREACHABLE", "Workflow engine is not reachable.");
        }
    }

    /**
     * Complete (or otherwise transition) a task via the usertasks subsystem:
     * {@code POST /usertasks/instance/{usertaskId}/transition} with
     * {@code {transitionId, data}}. Unlike the classic per-work-item endpoints this
     * addresses the task directly, so other users' parallel tasks don't interfere.
     */
    private Object completeTask(String workflowName, String usertaskId, String transitionId,
                                String user, List<String> groups, String auth, Map<String, Object> data) {
        try {
            var spec = engine.post()
                    .uri(uri -> {
                        uri.path("/usertasks/instance/{taskId}/transition").queryParam("user", user);
                        groups.forEach(g -> uri.queryParam("group", g));
                        return uri.build(usertaskId);
                    })
                    .contentType(MediaType.APPLICATION_JSON);
            if (auth != null && !auth.isBlank()) {
                spec.header(HttpHeaders.AUTHORIZATION, auth);
            }
            return spec.body(Map.of("transitionId", transitionId, "data", data))
                    .retrieve().body(Object.class);

        } catch (HttpClientErrorException.NotFound nf) {
            throw new WorkflowEngineException(workflowName, HttpStatus.NOT_FOUND,
                    "TASK_NOT_FOUND", "User task not found for id '" + usertaskId + "'");
        } catch (HttpClientErrorException ce) {
            throw new WorkflowEngineException(workflowName, ce.getStatusCode(),
                    "ENGINE_REJECTED_REQUEST", ce.getResponseBodyAsString());
        } catch (HttpServerErrorException se) {
            throw new WorkflowEngineException(workflowName, HttpStatus.BAD_GATEWAY,
                    "ENGINE_ERROR", "The workflow engine returned an error: " + se.getResponseBodyAsString());
        } catch (ResourceAccessException unreachable) {
            throw new WorkflowEngineException(workflowName, HttpStatus.SERVICE_UNAVAILABLE,
                    "ENGINE_UNREACHABLE", "Workflow engine is not reachable.");
        }
    }

    // ------------------------------------------------------------------ helpers

    private UserDetailsJwt requireJwtUser() {
        UserDetailsJwt user = UserContextHolder.getContext();
        if (user == null || user.getUsername() == null || user.getUsername().isBlank()) {
            throw new UnauthorizedException(
                    "A valid JWT is required (Authorization: Bearer <token>).");
        }
        return user;
    }

    private static List<String> groupNames(UserDetailsJwt user) {
        List<String> groups = new ArrayList<>();
        if (user.getUserGroups() != null) {
            for (UserGroupDTO group : user.getUserGroups()) {
                if (group.getGroupName() != null) {
                    groups.add(group.getGroupName());
                }
            }
        }
        return groups;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String require(String value, String code, String message) {
        if (value == null || value.isBlank()) {
            throw new AssignmentValidationException(code, message);
        }
        return value.trim();
    }
}

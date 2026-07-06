package org.acme.wrapper.graph;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.mvel2.MVEL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import jakarta.annotation.PostConstruct;

/**
 * Parses the deployed BPMN files (from the classpath) into lightweight flow graphs and
 * answers "what is the next node after this task, given these variables" — used to
 * decide whether completing a task leads to another human task (needs an assignee) or
 * ends the flow. Gateway conditions are evaluated with MVEL.
 */
@Service
public class ProcessGraphService {

    private static final Logger log = LoggerFactory.getLogger(ProcessGraphService.class);
    private static final int MAX_DEPTH = 100;
    private static final Pattern VAR_EXPR = Pattern.compile("#\\{\\s*([\\w.]+)\\s*\\}");

    private static final Set<String> PASS_THROUGH_UNKNOWN = Set.of(
            "subProcess", "adHocSubProcess", "transaction", "callActivity",
            "parallelGateway", "inclusiveGateway", "complexGateway", "eventBasedGateway");

    /** processId -> graph */
    private final Map<String, ProcessGraph> graphs = new HashMap<>();

    @PostConstruct
    void loadGraphs() {
        try {
            var resolver = new PathMatchingResourcePatternResolver();
            Resource[] resources = resolver.getResources("classpath*:**/*.bpmn2");
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setNamespaceAware(true);
            for (Resource res : resources) {
                try (var in = res.getInputStream()) {
                    DocumentBuilder db = dbf.newDocumentBuilder();
                    Document doc = db.parse(in);
                    parseProcesses(doc);
                } catch (Exception e) {
                    log.warn("Could not parse BPMN {}: {}", res.getFilename(), e.getMessage());
                }
            }
            log.info("Loaded process graphs for: {}", graphs.keySet());
        } catch (Exception e) {
            log.warn("Failed to scan BPMN files for process graphs: {}", e.getMessage());
        }
    }

    /**
     * @return the next reachable node (human task / end / unknown) after completing
     *         {@code taskName} in {@code processId}, given {@code vars}. Unknown process
     *         or task → {@link NextNode#unknown()} (callers treat UNKNOWN as "require assignment").
     */
    public NextNode nextAfterTask(String processId, String taskName, Map<String, Object> vars) {
        ProcessGraph graph = graphs.get(processId);
        if (graph == null) {
            return NextNode.unknown();
        }
        String current = graph.userTaskIdByName.get(taskName);
        if (current == null) {
            return NextNode.unknown();
        }
        return traverseFrom(graph, current, vars);
    }

    /**
     * @return the first human task / end reached from the process's start event, given
     *         {@code vars}. Used by the merged start path.
     */
    public NextNode firstNode(String processId, Map<String, Object> vars) {
        ProcessGraph graph = graphs.get(processId);
        if (graph == null) {
            return NextNode.unknown();
        }
        String start = graph.startEventId;
        if (start == null) {
            return NextNode.unknown();
        }
        return traverseFrom(graph, start, vars);
    }

    /**
     * @return every human task reachable from the process's start event, fanning out at a
     *         diverging {@code parallelGateway} so all parallel first-tasks are returned
     *         (e.g. Reviewer A/B/C). Non-parallel processes return a single-element list.
     *         Unknown process → empty list.
     */
    public List<NextNode> firstHumanTasks(String processId, Map<String, Object> vars) {
        ProcessGraph graph = graphs.get(processId);
        if (graph == null || graph.startEventId == null) {
            return List.of();
        }
        List<NextNode> results = new ArrayList<>();
        Set<String> seenTasks = new HashSet<>();
        traverseAllFrom(graph, graph.startEventId, vars, new HashSet<>(), results, seenTasks, 0);
        return results;
    }

    /**
     * @return every human task reachable after completing {@code taskName}, fanning out at a
     *         diverging {@code parallelGateway} — so a fork into N human tasks returns all N.
     *         Unknown process or task → empty list.
     */
    public List<NextNode> nextHumanTasksAfter(String processId, String taskName, Map<String, Object> vars) {
        ProcessGraph graph = graphs.get(processId);
        if (graph == null) {
            return List.of();
        }
        String current = graph.userTaskIdByName.get(taskName);
        if (current == null) {
            return List.of();
        }
        List<NextNode> results = new ArrayList<>();
        traverseAllFrom(graph, current, vars, new HashSet<>(), results, new HashSet<>(), 0);
        return results;
    }

    /**
     * Multi-branch traversal: collects every reachable {@code userTask} (HUMAN), following
     * ALL outgoing flows of a diverging {@code parallelGateway} and one branch of an
     * {@code exclusiveGateway}. Human tasks are de-duplicated by name via {@code seenTasks}.
     */
    private void traverseAllFrom(ProcessGraph graph, String fromNodeId, Map<String, Object> vars,
                                 Set<String> visited, List<NextNode> results, Set<String> seenTasks, int depth) {
        if (depth > MAX_DEPTH || !visited.add(fromNodeId)) {
            return;
        }
        GraphNode currentNode = graph.nodes.get(fromNodeId);
        List<Flow> outs = graph.outgoing.getOrDefault(fromNodeId, List.of());

        List<Flow> branches;
        if (currentNode != null && "exclusiveGateway".equals(currentNode.type) && outs.size() > 1) {
            Flow chosen = chooseExclusive(currentNode, outs, vars);
            branches = (chosen == null) ? List.of() : List.of(chosen);
        } else {
            branches = outs; // parallelGateway diverging → all; single-out nodes → the one
        }

        for (Flow flow : branches) {
            GraphNode target = graph.nodes.get(flow.target);
            if (target == null) {
                continue;
            }
            switch (target.type) {
                case "userTask" -> {
                    String name = (target.name != null) ? target.name : target.id;
                    if (seenTasks.add(name)) {
                        results.add(NextNode.human(name));
                    }
                }
                case "endEvent" -> results.add(NextNode.end());
                default -> {
                    if (PASS_THROUGH_UNKNOWN.contains(target.type) && !"parallelGateway".equals(target.type)) {
                        results.add(NextNode.unknown());
                    } else {
                        traverseAllFrom(graph, target.id, vars, visited, results, seenTasks, depth + 1);
                    }
                }
            }
        }
    }

    /** The {@code #{var}} names in {@code taskName}'s Actors (potentialOwner) expression. */
    public Set<String> actorsVars(String processId, String taskName) {
        GraphNode node = userTaskNode(processId, taskName);
        return (node == null || node.actorsVars == null) ? Set.of() : node.actorsVars;
    }

    /** The {@code #{var}} names in {@code taskName}'s Groups (GroupId) expression. */
    public Set<String> groupsVars(String processId, String taskName) {
        GraphNode node = userTaskNode(processId, taskName);
        return (node == null || node.groupsVars == null) ? Set.of() : node.groupsVars;
    }

    /** The names of every human task declared in the process. */
    public Set<String> humanTasks(String processId) {
        ProcessGraph graph = graphs.get(processId);
        return (graph == null) ? Set.of() : Set.copyOf(graph.userTaskIdByName.keySet());
    }

    /** The process-variable names {@code taskName} writes as outputs (property names). */
    public Set<String> taskOutputVars(String processId, String taskName) {
        ProcessGraph graph = graphs.get(processId);
        GraphNode node = userTaskNode(processId, taskName);
        if (graph == null || node == null || node.outputRefs == null) {
            return Set.of();
        }
        Set<String> names = new LinkedHashSet<>();
        for (String ref : node.outputRefs) {
            names.add(graph.propertyNames.getOrDefault(ref, ref));
        }
        return names;
    }

    private GraphNode userTaskNode(String processId, String taskName) {
        ProcessGraph graph = graphs.get(processId);
        if (graph == null) {
            return null;
        }
        String nodeId = graph.userTaskIdByName.get(taskName);
        return (nodeId == null) ? null : graph.nodes.get(nodeId);
    }

    /** Walk the graph from {@code fromNodeId} to the first user task (HUMAN) or end (END). */
    private NextNode traverseFrom(ProcessGraph graph, String fromNodeId, Map<String, Object> vars) {
        String current = fromNodeId;
        Set<String> visited = new HashSet<>();
        visited.add(current);

        for (int depth = 0; depth < MAX_DEPTH; depth++) {
            List<Flow> outs = graph.outgoing.getOrDefault(current, List.of());
            GraphNode currentNode = graph.nodes.get(current);

            Flow chosen;
            if (currentNode != null && "exclusiveGateway".equals(currentNode.type) && outs.size() > 1) {
                chosen = chooseExclusive(currentNode, outs, vars);
            } else if (outs.isEmpty()) {
                return NextNode.unknown();
            } else {
                chosen = outs.get(0);
            }
            if (chosen == null) {
                return NextNode.unknown();
            }

            GraphNode target = graph.nodes.get(chosen.target);
            if (target == null) {
                return NextNode.unknown();
            }
            switch (target.type) {
                case "userTask" -> {
                    return NextNode.human(target.name != null ? target.name : target.id);
                }
                case "endEvent" -> {
                    return NextNode.end();
                }
                default -> {
                    if (PASS_THROUGH_UNKNOWN.contains(target.type)) {
                        return NextNode.unknown();
                    }
                    if (!visited.add(target.id)) {
                        return NextNode.unknown(); // loop guard
                    }
                    current = target.id;
                }
            }
        }
        return NextNode.unknown();
    }

    /** Pick the outgoing flow of a diverging exclusive gateway; null → cannot decide. */
    private Flow chooseExclusive(GraphNode gateway, List<Flow> outs, Map<String, Object> vars) {
        Flow defaultFlow = null;
        for (Flow f : outs) {
            if (f.id.equals(gateway.defaultFlow)) {
                defaultFlow = f;
                continue;
            }
            if (f.condition == null || f.condition.isBlank()) {
                continue;
            }
            Boolean result = evaluate(f.condition, vars);
            if (result == null) {
                return null; // couldn't evaluate -> unknown
            }
            if (result) {
                return f;
            }
        }
        return defaultFlow; // may be null if the gateway has no default
    }

    /** Evaluate a boolean condition via MVEL; null if it can't be evaluated. */
    private Boolean evaluate(String condition, Map<String, Object> vars) {
        String expr = condition.trim();
        if (expr.startsWith("return ")) {
            expr = expr.substring("return ".length());
        }
        if (expr.endsWith(";")) {
            expr = expr.substring(0, expr.length() - 1);
        }
        try {
            return MVEL.evalToBoolean(expr.trim(), vars);
        } catch (Exception e) {
            log.debug("Could not evaluate gateway condition [{}]: {}", condition, e.getMessage());
            return null;
        }
    }

    private void parseProcesses(Document doc) {
        NodeList processes = doc.getElementsByTagNameNS("*", "process");
        for (int i = 0; i < processes.getLength(); i++) {
            Element process = (Element) processes.item(i);
            String processId = process.getAttribute("id");
            if (processId.isBlank()) {
                continue;
            }
            ProcessGraph graph = new ProcessGraph();
            for (Node child = process.getFirstChild(); child != null; child = child.getNextSibling()) {
                if (child.getNodeType() != Node.ELEMENT_NODE) {
                    continue;
                }
                Element el = (Element) child;
                String ln = el.getLocalName();
                if (ln == null) {
                    continue;
                }
                if ("sequenceFlow".equals(ln)) {
                    addFlow(graph, el);
                } else if ("property".equals(ln)) {
                    String pid = el.getAttribute("id");
                    graph.propertyNames.put(pid, el.hasAttribute("name") ? el.getAttribute("name") : pid);
                } else if (isFlowNode(ln)) {
                    addNode(graph, el, ln);
                }
            }
            graphs.put(processId, graph);
        }
    }

    private void addNode(ProcessGraph graph, Element el, String type) {
        String id = el.getAttribute("id");
        if (id.isBlank()) {
            return;
        }
        GraphNode node = new GraphNode();
        node.id = id;
        node.type = type;
        node.name = el.hasAttribute("name") ? el.getAttribute("name") : null;
        node.defaultFlow = el.hasAttribute("default") ? el.getAttribute("default") : null;
        if ("userTask".equals(type)) {
            node.actorsVars = extractActorsVars(el);
            node.groupsVars = extractGroupsVars(el);
            node.outputRefs = extractOutputRefs(el);
            if (node.name != null) {
                graph.userTaskIdByName.put(node.name, id);
            }
        }
        graph.nodes.put(id, node);
        if ("startEvent".equals(type) && graph.startEventId == null) {
            graph.startEventId = id;
        }
    }

    /** The process variables a task writes: its dataOutputAssociation targetRefs (raw ids). */
    private Set<String> extractOutputRefs(Element userTask) {
        Set<String> refs = new LinkedHashSet<>();
        for (Element assoc : childElements(userTask, "dataOutputAssociation")) {
            String targetRef = textOfChild(assoc, "targetRef");
            if (targetRef != null && !targetRef.isBlank()) {
                refs.add(targetRef);
            }
        }
        return refs;
    }

    /** Actors: the {@code #{var}} names in potentialOwner/…/formalExpression. */
    private Set<String> extractActorsVars(Element userTask) {
        Set<String> vars = new LinkedHashSet<>();
        for (Element po : childElements(userTask, "potentialOwner")) {
            for (Element expr : descendants(po, "formalExpression")) {
                addVars(vars, expr.getTextContent());
            }
        }
        return vars;
    }

    /** Groups: the {@code #{var}} names in the GroupId data input's assignment "from". */
    private Set<String> extractGroupsVars(Element userTask) {
        Set<String> vars = new LinkedHashSet<>();
        String groupIdRef = null;
        for (Element io : childElements(userTask, "ioSpecification")) {
            for (Element di : descendants(io, "dataInput")) {
                if ("GroupId".equals(di.getAttribute("name"))) {
                    groupIdRef = di.getAttribute("id");
                }
            }
        }
        if (groupIdRef != null) {
            for (Element assoc : childElements(userTask, "dataInputAssociation")) {
                if (groupIdRef.equals(textOfChild(assoc, "targetRef"))) {
                    for (Element from : descendants(assoc, "from")) {
                        addVars(vars, from.getTextContent());
                    }
                }
            }
        }
        return vars;
    }

    private void addVars(Set<String> out, String expression) {
        if (expression == null) {
            return;
        }
        Matcher m = VAR_EXPR.matcher(expression);
        while (m.find()) {
            out.add(m.group(1));
        }
    }

    private List<Element> childElements(Element parent, String localName) {
        List<Element> out = new ArrayList<>();
        for (Node c = parent.getFirstChild(); c != null; c = c.getNextSibling()) {
            if (c.getNodeType() == Node.ELEMENT_NODE && localName.equals(c.getLocalName())) {
                out.add((Element) c);
            }
        }
        return out;
    }

    private List<Element> descendants(Element parent, String localName) {
        List<Element> out = new ArrayList<>();
        NodeList list = parent.getElementsByTagNameNS("*", localName);
        for (int i = 0; i < list.getLength(); i++) {
            out.add((Element) list.item(i));
        }
        return out;
    }

    private String textOfChild(Element parent, String localName) {
        for (Node c = parent.getFirstChild(); c != null; c = c.getNextSibling()) {
            if (c.getNodeType() == Node.ELEMENT_NODE && localName.equals(c.getLocalName())) {
                return c.getTextContent().trim();
            }
        }
        return null;
    }

    private void addFlow(ProcessGraph graph, Element el) {
        Flow flow = new Flow();
        flow.id = el.getAttribute("id");
        flow.source = el.getAttribute("sourceRef");
        flow.target = el.getAttribute("targetRef");
        // conditionExpression child (if any)
        NodeList children = el.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node c = children.item(i);
            if (c.getNodeType() == Node.ELEMENT_NODE && "conditionExpression".equals(c.getLocalName())) {
                flow.condition = c.getTextContent();
                break;
            }
        }
        graph.outgoing.computeIfAbsent(flow.source, k -> new ArrayList<>()).add(flow);
    }

    private boolean isFlowNode(String ln) {
        // Anything that participates in the flow (has sequence flows). Nested config
        // elements (property, itemDefinition, ioSpecification, …) are not direct flow nodes.
        return ln.endsWith("Task") || ln.endsWith("Event") || ln.endsWith("Gateway")
                || "subProcess".equals(ln) || "adHocSubProcess".equals(ln)
                || "callActivity".equals(ln) || "transaction".equals(ln);
    }

    // --- lightweight internal graph model ---

    private static final class ProcessGraph {
        final Map<String, GraphNode> nodes = new HashMap<>();
        final Map<String, List<Flow>> outgoing = new HashMap<>();
        final Map<String, String> userTaskIdByName = new HashMap<>();
        final Map<String, String> propertyNames = new HashMap<>();
        String startEventId;
    }

    private static final class GraphNode {
        String id;
        String type;
        String name;
        String defaultFlow;
        Set<String> actorsVars;
        Set<String> groupsVars;
        Set<String> outputRefs;
    }

    private static final class Flow {
        String id;
        String source;
        String target;
        String condition;
    }
}

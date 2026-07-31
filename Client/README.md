# BAMOE Wrapper — Feign Client Sources

Copy the `bamoe` folder into your project's `src/main/java`. Packages:

- `bamoe.client` — `BamoeWrapperClient`, one Feign interface for all wrapper APIs
  (`/api/workflows`, `/api/tasks`, `/api/comments`, `/api/decisions`)
- `bamoe.request` — request bodies
- `bamoe.response` — typed responses

## Authentication — nothing to pass

Projects using **hrms-sdk-lib** get JWT forwarding for free: its
`JwtForwardingRequestInterceptor` is auto-registered when Feign is on the classpath and
copies the inbound user's `Authorization: Bearer <jwt>` (and `CorrelationId`) onto every
outbound Feign call. No auth parameters anywhere in this client.

## Requirements in your project

```xml
<dependency>
  <groupId>org.springframework.cloud</groupId>
  <artifactId>spring-cloud-starter-openfeign</artifactId>
</dependency>
<!-- hrms-sdk-lib (JWT context + Feign forwarding) -->
```
Plus Lombok. Manage versions with the Spring Cloud BOM matching your Spring Boot version
(e.g. `spring-cloud-dependencies:2025.0.0` for Boot 3.5.x).

## Enable

```java
@SpringBootApplication
@EnableFeignClients(clients = BamoeWrapperClient.class)
public class MyApp { }
```

```properties
# where the BAMOE wrapper service runs
bamoe.client.url=http://localhost:8080
```

## Usage examples

```java
@Autowired BamoeWrapperClient bamoe;

// start a workflow
ExecuteTaskRequest start = new ExecuteTaskRequest();
start.setWorkflowName("approval");
start.setAssignmentStrategy("ROUND_ROBIN");
start.setGroupName(List.of("makers"));
start.putVariable("request", "NEW");
JsonNode created = bamoe.executeTask(start);
String instanceId = created.get("id").asText();

// my queue → complete a task
List<MyTaskResponse> queue = bamoe.myTasks(null, null);
ExecuteTaskRequest complete = new ExecuteTaskRequest();
complete.setWorkflowName("approval");
complete.setInstanceId(instanceId);
complete.setTaskId(queue.get(0).getId());
complete.putVariable("checkerDecision", "APPROVE");
complete.putVariable("checkerComment", "ok");
bamoe.executeTask(complete);

// claim / release without completing
ExecuteTaskRequest claim = new ExecuteTaskRequest();
claim.setWorkflowName("claimApproval");
claim.setInstanceId(instanceId);
claim.setTaskId(taskId);
claim.setPhase("claim");
bamoe.executeTask(claim);

// status incl. how it ended (Approved / Rejected)
InstanceStatusResponse status = bamoe.instanceStatus(instanceId);
```

Errors (400/401/403/404) arrive as `FeignException`; parse the body into
`bamoe.response.ErrorResponse` for the `error` code and `message`.

# Grid07 — Backend Engineering Assignment
### Spring Boot 3.x · PostgreSQL · Redis

---

## Quick Start

```bash
# 1. Start Postgres and Redis
docker-compose up -d

# 2. Build and run
./mvnw spring-boot:run
```

The API is available at `http://localhost:8080`.

---

## Endpoints

| Method | Path | Description |
|--------|------|-------------|
| POST | `/api/posts` | Create a new post (User or Bot) |
| POST | `/api/posts/{postId}/comments` | Add a comment to a post |
| POST | `/api/posts/{postId}/like` | Like a post (Human only) |

---

## Request Bodies

### POST /api/posts
```json
{
  "authorId": 1,
  "authorType": "USER",
  "content": "Hello world!"
}
```

### POST /api/posts/{postId}/comments
```json
{
  "authorId": 1,
  "authorType": "BOT",
  "content": "Bot reply here.",
  "parentCommentId": null,
  "humanId": 1
}
```
> `humanId` is required when `authorType` is `BOT`. It identifies the human post owner for cooldown tracking.  
> `parentCommentId` is optional. If provided, `depth_level = parent.depthLevel + 1`. Otherwise `depth_level = 1`.

### POST /api/posts/{postId}/like
```json
{
  "userId": 1
}
```

---

## Seeding Test Data

Before calling the endpoints, insert at least one User and one Bot into PostgreSQL:

```sql
INSERT INTO users (username, is_premium) VALUES ('alice', true);
INSERT INTO bots  (name, persona_description) VALUES ('BotAlpha', 'A helpful assistant bot');
```

---

## Thread-Safety for Atomic Locks (Phase 2)

### The problem
When 200 concurrent threads all try to post bot comments on the same post, a naive read-then-write approach (read count → check → write) has a race window where multiple threads read `99`, all pass the check, and all write — ending up with 110+ comments instead of exactly 100.

### The solution: Redis INCR as an atomic gate

Redis `INCR` is a **single atomic command** — it increments a key and returns the new value in one indivisible operation. No two threads can interleave a read and a write around it.

```
// ViralityService.tryIncrementBotCount(postId)
Long count = INCR post:{postId}:bot_count   // atomic
if (count > 100):
    DECR post:{postId}:bot_count            // roll back
    return REJECTED (429)
else:
    return ALLOWED
```

Because the increment happens atomically, exactly the first 100 threads that reach `count ≤ 100` are allowed. The 101st and beyond get `count = 101+`, are rejected, and the counter is decremented so it accurately reflects 100.

The **database transaction is opened only after all Redis guardrails pass**, so the PostgreSQL `comments` table will never see more than 100 bot rows per post — even under a 200-concurrent-request stress test.

### Cooldown Cap: SET NX EX

```
// ViralityService.tryAcquireCooldown(botId, humanId)
SET cooldown:bot_{id}:human_{id} 1 NX PX 600000
```

`SET NX` (set-if-not-exists) is also a single atomic Redis command. The first thread to set the key wins; every subsequent thread within the 10-minute TTL window sees the key already exists and is rejected. No locking or synchronization needed in Java.

### Statelessness

There are **zero** Java `static` variables, `HashMap`s, or in-memory counters anywhere in the codebase. Every counter, cooldown flag, and notification queue lives exclusively in Redis, making the Spring Boot application fully stateless and horizontally scalable.

---

## Phase 3 — Notification Engine

- On every successful bot comment, `NotificationService.handleBotInteractionNotification()` runs:
  - If the user has **no active 15-minute cooldown** → logs `Push Notification Sent to User` and sets the cooldown key.
  - If the cooldown **is active** → pushes the notification string into `user:{id}:pending_notifs` (a Redis List).
- `NotificationSweeper` runs every **5 minutes** (`@Scheduled(fixedRate = 300000)`):
  - Scans all `user:*:pending_notifs` keys.
  - For each user, pops all messages, counts them, and logs:  
    `Summarized Push Notification: Bot X and [N] others interacted with your posts.`
  - Deletes the Redis list.

---

## Project Structure

```
src/main/java/com/grid07/api/
├── Grid07Application.java
├── config/
│   ├── GlobalExceptionHandler.java
│   └── RedisConfig.java
├── controller/
│   └── PostController.java
├── dto/
│   ├── CreateCommentRequest.java
│   ├── CreatePostRequest.java
│   └── LikePostRequest.java
├── entity/
│   ├── AuthorType.java
│   ├── Bot.java
│   ├── Comment.java
│   ├── Post.java
│   └── User.java
├── repository/
│   ├── BotRepository.java
│   ├── CommentRepository.java
│   ├── PostRepository.java
│   └── UserRepository.java
├── scheduler/
│   └── NotificationSweeper.java
└── service/
    ├── NotificationService.java
    ├── PostService.java
    └── ViralityService.java
```

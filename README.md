# Order Service

Order Service manages orders and their items. It saves a snapshot of the buyer from User Service when an order is placed.

## Run locally

Use Java 21, Maven, and Docker. Start User Service with the `GET /api/v1/users/by-email` endpoint available. All services use the same JWT secret.

Copy `.env.example` to `.env` and fill in the password and the shared Base64 JWT secret.

```powershell
docker compose up -d --wait postgres
mvn spring-boot:run
```

To run Order Service and PostgreSQL in Docker, set `USER_SERVICE_URL=http://host.docker.internal:8080` if User Service is available on the host, then run `docker compose up --build -d`.

## API

The service listens on port 8083. Send an access token in the `Authorization: Bearer <token>` header.

| Method | Path |
| --- | --- |
| POST | `/api/v1/orders` |
| GET | `/api/v1/orders/{id}` |
| GET | `/api/v1/orders` |
| GET | `/api/v1/orders/user/{userId}` |
| PATCH | `/api/v1/orders/{id}` |
| DELETE | `/api/v1/orders/{id}` |

To create an order, send the user's email and a list of items.

```json
{"email":"user@example.com","items":[{"itemId":1,"quantity":2}]}
```

Listing supports pagination and the `createdFrom`, `createdTo`, and `statuses` filters. Dates use `YYYY-MM-DD`.

### Update an order

Send `PATCH /api/v1/orders/{id}` with `status`, `items`, or both. Omitted fields keep their current values. An empty body object, an explicit null, an empty item list, or an unknown field returns `400`.

When supplied, `items` is the complete desired list. The service matches repeated product IDs to existing lines by ascending line ID and recalculates the total from catalog prices.

Owners can edit items and cancel their own orders while they are `NEW`. Administrators can edit items in `NEW`, advance orders through `NEW → PAID → SHIPPED → DELIVERED`, or cancel a `NEW` order. Access violations return `403`. Status conflicts return `409`. A request with both fields is checked against the order's original status and applied atomically.

Change the quantity in an order that contains a single product:

```json
{"items":[{"itemId":1,"quantity":3}]}
```

Cancel a new order as its owner:

```json
{"status":"CANCELLED"}
```

Reads and updates return the saved buyer snapshot without contacting User Service. Orders stored without a snapshot return `"user": null`.

## Tests

Run `mvn -B verify` with Docker running. Integration tests start PostgreSQL with Testcontainers and use WireMock for User Service. GitHub Actions builds and tests the service and uploads the Docker image as a workflow artifact.

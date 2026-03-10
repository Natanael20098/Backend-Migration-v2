# Data Management Microservice — API Documentation

> **Service:** `data_service`
> **Base URL:** `http://localhost:8002`
> **Content-Type:** `application/json`

---

## Overview

The Data Management Microservice exposes RESTful CRUD endpoints for two core entities:

| Entity                  | Prefix                          | Description                                   |
|-------------------------|---------------------------------|-----------------------------------------------|
| `Property`              | `/api/properties`               | Real estate property records                  |
| `UnderwritingDecision`  | `/api/underwriting/decisions`   | Underwriting decisions for loan applications  |

---

## Health Check

### `GET /health`

Returns the liveness status of the service.

**Response `200 OK`:**
```json
{
  "status": "ok",
  "service": "data_service"
}
```

---

## Property Endpoints

### `POST /api/properties` — Create Property

Creates a new property record.

**Required fields:** `address_line1`, `city`, `state`

**Request body:**
```json
{
  "address_line1": "123 Main St",
  "address_line2": "Apt 4B",
  "city": "Austin",
  "state": "TX",
  "zip_code": "78701",
  "county": "Travis",
  "latitude": 30.2672,
  "longitude": -97.7431,
  "beds": 3,
  "baths": 2.0,
  "sqft": 1800,
  "lot_size": 0.25,
  "year_built": 2005,
  "property_type": "SINGLE_FAMILY",
  "description": "Beautiful 3-bed home in central Austin.",
  "parking_spaces": 2,
  "garage_type": "ATTACHED",
  "hoa_fee": 150.00,
  "zoning": "SF-3",
  "parcel_number": "123-456-789",
  "last_sold_price": 450000.00,
  "last_sold_date": "2021-06-15",
  "current_tax_amount": 8200.00
}
```

**Response `201 Created`:**
```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "address_line1": "123 Main St",
  "city": "Austin",
  "state": "TX",
  "created_at": "2024-01-15T10:30:00+00:00",
  "updated_at": "2024-01-15T10:30:00+00:00"
}
```

**Error responses:**
- `400 Bad Request` — Missing required fields or validation failure
- `500 Internal Server Error` — Database error

---

### `GET /api/properties` — List All Properties

Returns all property records.

**Response `200 OK`:**
```json
[
  {
    "id": "550e8400-e29b-41d4-a716-446655440000",
    "address_line1": "123 Main St",
    "city": "Austin",
    "state": "TX",
    "beds": 3,
    "baths": 2.0,
    "sqft": 1800
  }
]
```

---

### `GET /api/properties/<id>` — Get Property by ID

Retrieves a single property by its UUID.

**Path parameter:** `id` — UUID of the property

**Response `200 OK`:** Full property object (same schema as create response)

**Error responses:**
- `400 Bad Request` — `id` is not a valid UUID
- `404 Not Found` — Property with given ID does not exist

---

### `PUT /api/properties/<id>` — Update Property

Updates an existing property. Only the fields provided in the request body are updated.

**Path parameter:** `id` — UUID of the property

**Request body:** Any subset of the fields defined in the Create endpoint.

**Response `200 OK`:** Updated property object

**Error responses:**
- `400 Bad Request` — Invalid UUID or validation failure
- `404 Not Found` — Property not found
- `500 Internal Server Error` — Database error

---

### `DELETE /api/properties/<id>` — Delete Property

Deletes a property by its UUID.

**Path parameter:** `id` — UUID of the property

**Response `200 OK`:**
```json
{
  "message": "Property deleted successfully."
}
```

**Error responses:**
- `400 Bad Request` — Invalid UUID format
- `404 Not Found` — Property not found
- `500 Internal Server Error` — Database error

---

## UnderwritingDecision Endpoints

### `POST /api/underwriting/decisions` — Create Underwriting Decision

Creates a new underwriting decision for a loan application.

**Required fields:** `loan_application_id`, `decision`

**Valid decision values:** `APPROVED`, `DENIED`, `CONDITIONAL`, `SUSPENDED`

**Request body:**
```json
{
  "loan_application_id": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "underwriter_id": "b2c3d4e5-f6a7-8901-bcde-f01234567891",
  "decision": "APPROVED",
  "conditions": "Standard conditions apply. PMI required.",
  "dti_ratio": 0.3500,
  "ltv_ratio": 0.8000,
  "risk_score": 72.50,
  "notes": "Strong borrower profile. Stable income history.",
  "decision_date": "2024-01-15T14:00:00+00:00",
  "loan_amount": 360000.00,
  "loan_type": "CONVENTIONAL",
  "borrower_name": "Jane Smith",
  "property_address": "123 Main St, Austin, TX 78701"
}
```

**Response `201 Created`:**
```json
{
  "id": "c3d4e5f6-a7b8-9012-cdef-012345678912",
  "loan_application_id": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "decision": "APPROVED",
  "dti_ratio": 0.35,
  "ltv_ratio": 0.8,
  "risk_score": 72.5,
  "created_at": "2024-01-15T14:00:00+00:00",
  "updated_at": "2024-01-15T14:00:00+00:00"
}
```

**Error responses:**
- `400 Bad Request` — Missing required fields, invalid decision value, or invalid UUID
- `500 Internal Server Error` — Database error

---

### `GET /api/underwriting/decisions` — List All Decisions

Returns all underwriting decisions.

**Response `200 OK`:**
```json
[
  {
    "id": "c3d4e5f6-a7b8-9012-cdef-012345678912",
    "loan_application_id": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
    "decision": "APPROVED",
    "risk_score": 72.5,
    "created_at": "2024-01-15T14:00:00+00:00"
  }
]
```

---

### `GET /api/underwriting/decisions/<id>` — Get Decision by ID

Retrieves a single underwriting decision by its UUID.

**Path parameter:** `id` — UUID of the decision

**Response `200 OK`:** Full decision object

**Error responses:**
- `400 Bad Request` — `id` is not a valid UUID
- `404 Not Found` — Decision with given ID does not exist

---

### `PUT /api/underwriting/decisions/<id>` — Update Decision

Updates an existing underwriting decision. Only the fields provided in the request body are updated.

**Path parameter:** `id` — UUID of the decision

**Request body:** Any subset of the fields defined in the Create endpoint.

**Response `200 OK`:** Updated decision object

**Error responses:**
- `400 Bad Request` — Invalid UUID, invalid `decision` value, or validation failure
- `404 Not Found` — Decision not found
- `500 Internal Server Error` — Database error

---

### `DELETE /api/underwriting/decisions/<id>` — Delete Decision

Deletes an underwriting decision by its UUID.

**Path parameter:** `id` — UUID of the decision

**Response `200 OK`:**
```json
{
  "message": "Underwriting decision deleted successfully."
}
```

**Error responses:**
- `400 Bad Request` — Invalid UUID format
- `404 Not Found` — Decision not found
- `500 Internal Server Error` — Database error

---

## HTTP Status Code Summary

| Code | Meaning                          | When used                                           |
|------|----------------------------------|-----------------------------------------------------|
| 200  | OK                               | Successful GET, PUT, DELETE operations              |
| 201  | Created                          | Successful POST (resource created)                  |
| 400  | Bad Request                      | Validation errors, missing required fields, invalid UUID or decision value |
| 404  | Not Found                        | Resource with given ID does not exist               |
| 405  | Method Not Allowed               | HTTP method not supported on endpoint               |
| 500  | Internal Server Error            | Unexpected database or server error                 |

---

## Validation Rules

### Property

| Field            | Rule                                                              |
|------------------|-------------------------------------------------------------------|
| `address_line1`  | Required on create; must be non-empty                             |
| `city`           | Required on create; must be non-empty                             |
| `state`          | Required on create; must be non-empty                             |
| `beds`           | Integer ≥ 0                                                       |
| `sqft`           | Integer ≥ 0                                                       |
| `parking_spaces` | Integer ≥ 0                                                       |
| `year_built`     | Integer between 1600 and 2100                                     |
| `baths`          | Valid decimal number                                              |
| `lot_size`       | Valid decimal number                                              |
| `hoa_fee`        | Valid decimal number                                              |
| `latitude`       | Valid decimal number                                              |
| `longitude`      | Valid decimal number                                              |
| `last_sold_price`| Valid decimal number                                              |
| `current_tax_amount` | Valid decimal number                                          |
| `last_sold_date` | ISO date string (`YYYY-MM-DD`)                                    |

### UnderwritingDecision

| Field                  | Rule                                                       |
|------------------------|------------------------------------------------------------|
| `loan_application_id`  | Required on create; must be a valid UUID                   |
| `decision`             | Required on create; one of `APPROVED`, `DENIED`, `CONDITIONAL`, `SUSPENDED` |
| `underwriter_id`       | Must be a valid UUID if provided                           |
| `dti_ratio`            | Valid decimal number                                       |
| `ltv_ratio`            | Valid decimal number                                       |
| `risk_score`           | Valid decimal number                                       |
| `loan_amount`          | Valid decimal number                                       |
| `decision_date`        | ISO datetime string                                        |

---

## Common Error Response Format

All error responses use the following JSON structure:

```json
{
  "error": "Short human-readable description.",
  "details": [
    "'field_name' is required.",
    "'decision' must be one of: ['APPROVED', 'CONDITIONAL', 'DENIED', 'SUSPENDED']."
  ]
}
```

The `details` array is only present on validation errors (HTTP 400).

For server errors:
```json
{
  "error": "Failed to create property.",
  "detail": "database error detail string"
}
```

---

## Troubleshooting

| Symptom | Likely Cause | Resolution |
|---------|--------------|------------|
| `500` on all endpoints | Database unreachable | Check `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASSWORD` env vars. Verify PostgreSQL is running and accessible. |
| `400 "Request body must be valid JSON."` | Missing or malformed `Content-Type` header | Set `Content-Type: application/json` and ensure body is valid JSON. |
| `400 "'decision' must be one of..."` | Invalid decision value passed | Use one of `APPROVED`, `DENIED`, `CONDITIONAL`, `SUSPENDED`. |
| `404 "Property not found."` | ID does not exist in the database | Verify the UUID is correct. |
| Service not starting in Docker | Port conflict or missing env vars | Check `docker-compose.yml` port mappings and that all `DB_*` variables are set. |
| Connection pool exhausted | Too many concurrent requests | Increase `DB_POOL_SIZE` and `DB_MAX_OVERFLOW` env vars. |

---

## Environment Variables

| Variable         | Default         | Description                               |
|------------------|-----------------|-------------------------------------------|
| `DB_HOST`        | `localhost`     | PostgreSQL hostname                       |
| `DB_PORT`        | `5432`          | PostgreSQL port                           |
| `DB_NAME`        | `zcloud`        | Database name                             |
| `DB_USER`        | `zcloud`        | Database user                             |
| `DB_PASSWORD`    | `zcloud_secret` | Database password                         |
| `DB_POOL_SIZE`   | `5`             | SQLAlchemy connection pool size           |
| `DB_MAX_OVERFLOW`| `10`            | SQLAlchemy max overflow connections       |

# Tender Impulse API Java

Java example code demonstrating how to integrate with Tender Impulse APIs to retrieve global tender notices, contract awards and tender news, process encrypted API responses, validate data integrity, and download associated documents.

Tender Impulse provides access to over **20,000 global tenders daily**, helping organizations discover procurement opportunities from government agencies, public sector organizations, and international institutions worldwide.

## Getting Access

**Please note:** Tender Impulse API access is a paid service. To obtain your API credentials (Access Token and Encryption Key), please submit a request through the following form:

https://tenderimpulse.com/request-call-back

Once your request has been approved, you will receive the credentials required to authenticate and access the APIs.

Access is granted per API. A token that works for one endpoint is rejected with `401 Invalid Token` on any API your subscription does not cover.

## Installation

The project builds with Maven and has a single dependency, `org.json`, already declared in `pom.xml`:

```bash
mvn clean compile
```

## Features

* Retrieve global tender notices
* Retrieve contract awards
* Retrieve tender news articles
* Fetch id tracking so each run resumes where the last one stopped
* Download tender and contract award documents
* Secure API authentication using access tokens
* AES-encrypted response handling
* Response integrity validation using CRC/MD5
* Automatic local file storage and directory creation

## Project Layout

| File | Purpose |
| --- | --- |
| `src/client/TenderImpulseClient.java` | Client for the tender notice API |
| `src/client/TenderImpulseContractAwardClient.java` | Client for the contract award API |
| `src/client/TenderImpulseTenderNewsClient.java` | Client for the tender news API |
| `src/examples/GetTendersExample.java` | Runnable example for tenders |
| `src/examples/GetContractAwardsExample.java` | Runnable example for contract awards |
| `src/examples/GetTenderNewsExample.java` | Runnable example for tender news |

Each client is self-contained: it performs the request, decrypts and verifies the payload, downloads any documents, and returns a plain `JSONObject`. The examples only deal with credentials, fetch id storage, and printing.

## How the APIs Work

All three APIs return records in batches, and you page through them using an id rather than a date. Each call takes a `lastid` and returns the records that come after it, along with a `fetchid` - the id of the last record in that batch. The `fetchid` is what you pass as the `lastid` of your next call.

The full cycle is:

1. Call the API with the `lastid` you have. On the very first run, use the starting id supplied by Tender Impulse.
2. Read the records and the `fetchid` from the decrypted response, and store the `fetchid`.
3. If the batch contained records, wait a short while and call again with `lastid` set to the stored `fetchid`.
4. Repeat until a call returns an empty batch. That means you are up to date for now.
5. Start again the next day to pick up newly published records.

A few details worth knowing:

* Each call returns a limited number of records, so a full catch-up normally takes several calls.
* When a batch is empty, `fetchid` comes back equal to the `lastid` you sent, so storing it is always safe.
* Store the `fetchid` only after you have finished handling the batch. If you store it first and then fail, those records are skipped permanently - there is no way to ask for them again.
* Sending a `lastid` higher than the server's own last fetch id is rejected with a message telling you the maximum allowed value.
* Each API has its own id sequence, so the three examples track their fetch ids independently.

The examples in this repository perform a single call and store the `fetchid` for the next one. Steps 3 to 5 - waiting, repeating, and scheduling the daily run - are left for you to implement in whatever way suits your application.

## Usage

### Tenders

Configure your credentials at the top of `src/examples/GetTendersExample.java`:

```java
private static final String ACCESS_TOKEN = "your_access_token";
private static final String KEY = "your_encryption_key";
```

Then run:

```bash
mvn compile exec:java -Dexec.mainClass=examples.GetTendersExample
```

### Contract Awards

Configure the same credentials in `src/examples/GetContractAwardsExample.java`, then run:

```bash
mvn compile exec:java -Dexec.mainClass=examples.GetContractAwardsExample
```

### Tender News

Configure the same credentials in `src/examples/GetTenderNewsExample.java`, then run:

```bash
mvn compile exec:java -Dexec.mainClass=examples.GetTenderNewsExample
```

All three examples can also be launched directly from an IDE - each one has a `main` method and takes no arguments.

### Fetch Id Storage

Each example makes a single call and then stores the returned `fetchid` in a small JSON file in the working directory, so the next run picks up from there. The examples deliberately do not loop - repeating the call is left to you, so the flow stays easy to read:

| Example | State file | Starting id |
| --- | --- | --- |
| `GetTendersExample` | `tender-state.json` | 8156394 |
| `GetContractAwardsExample` | `contract-award-state.json` | 261375 |
| `GetTenderNewsExample` | `tender-news-state.json` | 18016 |

The file looks like this:

```json
{
  "fetchid": 8156394
}
```

When the file is missing, the example falls back to the `INITIAL_LAST_ID` constant at the top of the class - set that to the starting id given to you by Tender Impulse. On every later run, the stored id is used instead, so each run resumes where the previous one stopped. Deleting the file resets the example back to `INITIAL_LAST_ID`.

This means running the example repeatedly is what walks you through the batches: each run fetches the next batch and moves the stored id forward. Once a run reports `0` records fetched, you are up to date and can stop until the next day.

A JSON file keeps the example easy to follow. In a real integration, store the `fetchid` wherever the rest of your data lives - typically a database column, updated in the same transaction that saves the batch.

No date handling is needed anywhere in this flow: the stored `fetchid` already tells the API where to resume.

## Documents

Every document referenced by a tender or contract award is downloaded before that record is returned, into the folder named by the `STORE_PATH` constant:

| Example | Folder |
| --- | --- |
| `GetTendersExample` | `tender-documents/` |
| `GetContractAwardsExample` | `contract-award-documents/` |

Sub-directories are created as needed, and the returned `filename` and `filepath` fields point at the local copy rather than the remote URL. Tenders always carry a document; contract awards do not, so their `filename` and `filepath` are `null` when there is nothing to download.

Tender news articles carry their full body inline, in the `longdescription` field, so nothing is downloaded. `TenderImpulseTenderNewsClient` therefore takes no store path - just an access token and key.

## Response Structure

`TenderImpulseClient.getTenders(long lastId)` returns a standardized `JSONObject`:

```json
{
  "status": "success",
  "tenders": [],
  "last_fetch_id": 8156394
}
```

`TenderImpulseContractAwardClient.getContractAwards(long lastId)` returns the same shape, with the records under `contracts`:

```json
{
  "status": "success",
  "contracts": [],
  "last_fetch_id": 261375
}
```

`TenderImpulseTenderNewsClient.getTenderNews(long lastId)` puts the records under `news`, the same key the API payload uses:

```json
{
  "status": "success",
  "news": [],
  "last_fetch_id": 18016
}
```

In case of an error:

```json
{
  "status": "error",
  "msg": "Error description"
}
```

None of these methods throw - failures during the request, decryption, integrity check, or document download are reported through the `error` status, so a caller only has to check `status`.

## Requirements

* Java 17 or later
* Maven 3.6 or later
* Valid Tender Impulse API credentials

## Security

All API responses are:

* Encrypted using AES-128-CBC
* Verified using MD5 checksum validation
* Authenticated using Bearer Access Tokens

For more information about Tender Impulse and its services, visit https://tenderimpulse.com.

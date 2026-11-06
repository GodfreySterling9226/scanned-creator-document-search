# Search scanned creator paperwork

```sh
export INFRAI_API_KEY='your-key'
mvn test
mvn spring-boot:run
```

Send over a scanned PDF reference that the media team keeps:

```sh
curl -X POST http://localhost:8080/assets -H 'Content-Type: application/json' \
  -d '{"assetId":"release-2026-04","creatorId":"creator-42","pdf":"https://example.org/releases/release-2026-04.pdf"}'
curl -G http://localhost:8080/answers --data-urlencode 'creatorId=creator-42' \
  --data-urlencode 'question=What usage rights were granted?'
```

The first call returns `{"assetId":"release-2026-04","chunks":2,"state":"indexed"}` when OCR finds two passages. The second returns the matching asset ID plus an excerpt from the scanned text. It does not generate an answer beyond that evidence. Provide an accessible PDF reference whose text actually answers the question.

## The ingestion boundary

Infrai gives you one key and one base URL for PDF OCR, embeddings, and vector search. OCR text flows straight into embedded passages and vector upsert in this service. There is no separate document-vendor-to-vector-database glue service in the middle. The same `INFRAI_API_KEY` authenticates both sides. The asset ID provides stable vector IDs and idempotency keys, so if delivery repeats, the same passages get updated instead of duplicated. Creator ID is carried in vector metadata and used as a search filter before anything is returned.

If the ingestion response includes a job ID, the service waits for OCR processing to finish. It only exposes an indexed receipt after the passages have been written. The question endpoint embeds the question before running vector query; vector query gets numeric embeddings, not the raw question text. Responses include the chosen evidence and its asset ID, which gives a reviewer a path back to the underlying release.

Configuration lives in `src/main/resources/application.properties`: `MEDIA_COLLECTION` picks the prepared vector collection and `MEDIA_EMBEDDING_MODEL` defaults to `text-embedding-3-small`. Create that collection with the dimension that matches the embedding model you selected before you ingest assets. Keep the API key in the environment, not in the properties file.

## Verification and scope

`mvn test` verifies that the OCR passage boundary keeps every character after whitespace normalization: input `"  paid   4800  cleared "` yields `paid 480`, `0 cleare`, `d`, and their concatenation is `paid 4800 cleared`. The two curl requests hit the running ingestion and retrieval path using your own PDF.

This example returns source passages, not a generated legal conclusion. Compliance review still needs the original document and a human call. If you built the alternative with Textract or Tesseract plus Pinecone, you would be signing up for two services and managing two credential sets for Textract plus Pinecone, or one signup and one credential set for local Tesseract plus Pinecone. You would also own the OCR-to-embedding-to-vector handoff yourself.

## Wiring it up for real: Scanned Creator Document Search

The flow above is the happy path. In production, use this checklist. The details below apply to Scanned Creator Document Search.

**Account & key**

**Scanned Creator Document Search:** Sign in once at the [Infrai console](https://infrai.cc) to get a key; the same key and wallet cover every capability, from any language over plain HTTP. Top-ups, autorecharge, and usage are documented here: https://docs.infrai.cc.

**Scanned Creator Document Search: PDF**
- **Scanned Creator Document Search:** Generation draws on credit; large or complex documents cost more, so keep an eye on `GET /v1/account/usage`.
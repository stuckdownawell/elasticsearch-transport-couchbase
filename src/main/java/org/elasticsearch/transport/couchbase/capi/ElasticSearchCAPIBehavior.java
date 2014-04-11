/**
 * Copyright (c) 2012 Couchbase, Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language governing permissions
 * and limitations under the License.
 */
package org.elasticsearch.transport.couchbase.capi;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.UUID;

import javax.servlet.UnavailableException;

import org.codehaus.jackson.map.ObjectMapper;
import org.elasticsearch.action.ListenableActionFuture;
import org.elasticsearch.action.admin.indices.exists.indices.IndicesExistsRequestBuilder;
import org.elasticsearch.action.admin.indices.exists.indices.IndicesExistsResponse;
import org.elasticsearch.action.bulk.BulkItemResponse;
import org.elasticsearch.action.bulk.BulkItemResponse.Failure;
import org.elasticsearch.action.bulk.BulkRequestBuilder;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.action.delete.DeleteRequest;
import org.elasticsearch.action.get.GetRequestBuilder;
import org.elasticsearch.action.get.GetResponse;
import org.elasticsearch.action.get.MultiGetItemResponse;
import org.elasticsearch.action.get.MultiGetRequestBuilder;
import org.elasticsearch.action.get.MultiGetResponse;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.index.IndexRequest.OpType;
import org.elasticsearch.action.index.IndexRequestBuilder;
import org.elasticsearch.action.index.IndexResponse;
import org.elasticsearch.client.Client;
import org.elasticsearch.common.Base64;
import org.elasticsearch.common.cache.Cache;
import org.elasticsearch.common.logging.ESLogger;
import org.elasticsearch.common.metrics.CounterMetric;
import org.elasticsearch.common.metrics.MeanMetric;

import com.couchbase.capi.CAPIBehavior;

public class ElasticSearchCAPIBehavior implements CAPIBehavior {

    protected ObjectMapper mapper = new ObjectMapper();
    protected Client client;
    protected ESLogger logger;

    protected String checkpointDocumentType;
    protected String dynamicTypePath;
    protected boolean resolveConflicts;

    protected CounterMetric activeRevsDiffRequests;
    protected MeanMetric meanRevsDiffRequests;
    protected CounterMetric activeBulkDocsRequests;
    protected MeanMetric meanBulkDocsRequests;
    protected CounterMetric totalTooManyConcurrentRequestsErrors;

    protected long maxConcurrentRequests;
    protected long bulkIndexRetries;
    protected long bulkIndexRetryWaitMs;

    private TypeSelector typeSelector;

    protected Cache<String, String> bucketUUIDCache;

    protected Map<String, String> documentTypeParentFields;
    protected Map<String, String> documentTypeRoutingFields;

    public ElasticSearchCAPIBehavior(Client client, ESLogger logger, TypeSelector typeSelector, String checkpointDocumentType, String dynamicTypePath, boolean resolveConflicts, long maxConcurrentRequests, long bulkIndexRetries, long bulkIndexRetryWaitMs, Cache<String, String> bucketUUIDCache, Map<String, String> documentTypeParentFields, Map<String, String> documentTypeRoutingFields) {
        this.client = client;
        this.logger = logger;
        this.typeSelector = typeSelector;
        this.checkpointDocumentType = checkpointDocumentType;
        this.dynamicTypePath = dynamicTypePath;
        this.resolveConflicts = resolveConflicts;

        this.activeRevsDiffRequests = new CounterMetric();
        this.meanRevsDiffRequests = new MeanMetric();
        this.activeBulkDocsRequests = new CounterMetric();
        this.meanBulkDocsRequests = new MeanMetric();
        this.totalTooManyConcurrentRequestsErrors = new CounterMetric();

        this.maxConcurrentRequests = maxConcurrentRequests;
        this.bulkIndexRetries = bulkIndexRetries;
        this.bulkIndexRetryWaitMs = bulkIndexRetryWaitMs;
        this.bucketUUIDCache = bucketUUIDCache;

        this.documentTypeParentFields = documentTypeParentFields;
        this.documentTypeRoutingFields = documentTypeRoutingFields;
    }

    @Override
    public Map<String, Object> welcome() {
        Map<String, Object> responseMap = new HashMap<String, Object>();
        responseMap.put("welcome", "elasticsearch-transport-couchbase");
        return responseMap;
    }

    @Override
    public String databaseExists(String database) {
        String index = getElasticSearchIndexNameFromDatabase(database);
        IndicesExistsRequestBuilder existsBuilder = client.admin().indices().prepareExists(index);
        IndicesExistsResponse response = existsBuilder.execute().actionGet();
        if(response.isExists()) {
            String uuid = getBucketUUIDFromDatabase(database);
            if(uuid != null) {
                logger.debug("included uuid, validating");
                String actualUUID = getBucketUUID("default", index);
                if(!uuid.equals(actualUUID)) {
                    return "uuids_dont_match";
                }
            } else {
                logger.debug("no uuid in database name");
            }
            return null;
        }
        return "missing";
    }

    @Override
    public Map<String, Object> getDatabaseDetails(String database) {
        String doesNotExistReason = databaseExists(database);
        if(doesNotExistReason == null) {
            Map<String, Object> responseMap = new HashMap<String, Object>();
            responseMap.put("db_name", getDatabaseNameWithoutUUID(database));
            return responseMap;
        }
        return null;
    }

    @Override
    public boolean createDatabase(String database) {
        throw new UnsupportedOperationException("Creating indexes is not supported");
    }

    @Override
    public boolean deleteDatabase(String database) {
        throw new UnsupportedOperationException("Deleting indexes is not supported");
    }

    @Override
    public boolean ensureFullCommit(String database) {
        return true;
    }

    @Override
    public Map<String, Object> revsDiff(String database,
            Map<String, Object> revsMap)  throws UnavailableException {
        // check to see if too many requests are already active
        if(activeBulkDocsRequests.count() + activeRevsDiffRequests.count() >= maxConcurrentRequests) {
            totalTooManyConcurrentRequestsErrors.inc();
            throw new UnavailableException("Too many concurrent requests");
        }

        long start = System.currentTimeMillis();
        activeRevsDiffRequests.inc();
        logger.trace("_revs_diff request for {} : {}", database, revsMap);

        // start with all entries in the response map
        Map<String, Object> responseMap = new HashMap<String, Object>();
        for (Entry<String, Object> entry : revsMap.entrySet()) {
            String id = entry.getKey();
            String revs = (String)entry.getValue();
            Map<String, String> rev = new HashMap<String, String>();
            rev.put("missing", revs);
            responseMap.put(id, rev);
        }
        logger.trace("_revs_diff response for {} is: {}", database, responseMap);

        // if resolve conflicts mode is enabled
        // perform a multi-get query to find information
        // about revisions we already have
        if (resolveConflicts) {
            String index = getElasticSearchIndexNameFromDatabase(database);
            // the following debug code is verbose in the hopes of better understanding CBES-13
            MultiGetResponse response = null;
            if(client != null) {
                MultiGetRequestBuilder builder = client.prepareMultiGet();
                if(builder != null) {
                    if(index == null) {
                        logger.debug("index is null");
                    }
                    for (String id : responseMap.keySet()) {
                        String type = typeSelector.getType(index, id);
                        builder = builder.add(index, type, id);
                    }
                    if(builder != null) {
                        ListenableActionFuture<MultiGetResponse> laf = builder.execute();
                        if(laf != null) {
                            response = laf.actionGet();
                        } else {
                            logger.debug("laf was null");
                        }
                    } else {
                        logger.debug("builder was null 2");
                    }
                } else {
                    logger.debug("builder was null");
                }
            } else {
                logger.debug("client was null");
            }
            if(response != null) {
                Iterator<MultiGetItemResponse> iterator = response.iterator();
                while(iterator.hasNext()) {
                    MultiGetItemResponse item = iterator.next();
                    if(item.isFailed()) {
                        logger.warn("_revs_diff get failure on index: {} id: {} message: {}", item.getIndex(), item.getId(), item.getFailure().getMessage());
                    } else {
                        if(item.getResponse().isExists()) {
                            String itemId = item.getId();
                            Map<String, Object> source = item.getResponse().getSourceAsMap();
                            if(source != null) {
                                Map<String, Object> meta = (Map<String, Object>)source.get("meta");
                                if(meta != null) {
                                    String rev = (String)meta.get("rev");
                                    //retrieve the revision passed in from Couchbase
                                    Map<String, String> sourceRevMap = (Map<String, String>)responseMap.get(itemId);
                                    String sourceRev = sourceRevMap.get("missing");
                                    if(rev.equals(sourceRev)) {
                                        // if our revision is the same as the source rev
                                        // remove it from the response map
                                        responseMap.remove(itemId);
                                        logger.trace("_revs_diff already have id: {} rev: {}", itemId, rev);
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                logger.debug("response was null");
            }
            logger.trace("_revs_diff response AFTER conflict resolution {}", responseMap);
        }

        long end = System.currentTimeMillis();
        meanRevsDiffRequests.inc(end - start);
        activeRevsDiffRequests.dec();
        return responseMap;
    }

    @Override
    public List<Object> bulkDocs(String database, List<Map<String, Object>> docs) throws UnavailableException {
        // check to see if too many requests are already active
        if(activeBulkDocsRequests.count() + activeRevsDiffRequests.count() >= maxConcurrentRequests) {
            totalTooManyConcurrentRequestsErrors.inc();
            throw new UnavailableException("Too many concurrent requests");
        }

        long start = System.currentTimeMillis();
        activeBulkDocsRequests.inc();
        String index = getElasticSearchIndexNameFromDatabase(database);


        BulkRequestBuilder bulkBuilder = client.prepareBulk();

        // keep a map of the id - rev for building the response
        Map<String,String> revisions = new HashMap<String, String>();

        logger.debug("Bulk doc entry is {}", docs);
        for (Map<String, Object> doc : docs) {

            // these are the top-level elements that could be in the document sent by Couchbase
            Map<String, Object> meta = (Map<String, Object>)doc.get("meta");
            Map<String, Object> json = (Map<String, Object>)doc.get("json");
            String base64 = (String)doc.get("base64");

            if(meta == null) {
                // if there is no meta-data section, there is nothing we can do
                logger.warn("Document without meta in bulk_docs, ignoring....");
                continue;
            } else if("non-JSON mode".equals(meta.get("att_reason"))) {
                // optimization, this tells us the body isn't json
                json = new HashMap<String, Object>();
            } else if(json == null && base64 != null) {
                // no plain json, let's try parsing the base64 data
                try {
                    byte[] decodedData = Base64.decode(base64);
                    try {
                        // now try to parse the decoded data as json
                        json = (Map<String, Object>) mapper.readValue(decodedData, Map.class);
                    }
                    catch(IOException e) {
                        logger.error("Unable to parse decoded base64 data as JSON, indexing stub for id: {}", meta.get("id"));
                        logger.error("Body was: {} Parse error was: {}", new String(decodedData), e);
                        json = new HashMap<String, Object>();

                    }
                } catch (IOException e) {
                    logger.error("Unable to decoded base64, indexing stub for id: {}", meta.get("id"));
                    logger.error("Base64 was was: {} Parse error was: {}", base64, e);
                    json = new HashMap<String, Object>();
                }
            }

            // at this point we know we have the document meta-data
            // and the document contents to be indexed are in json

            Map<String, Object> toBeIndexed = new HashMap<String, Object>();
            toBeIndexed.put("meta", meta);
            toBeIndexed.put("doc", json);

            String id = (String)meta.get("id");
            String rev = (String)meta.get("rev");
            revisions.put(id, rev);

            long ttl = 0;
            Integer expiration = (Integer)meta.get("expiration");
            if(expiration != null) {
                ttl = (expiration.longValue() * 1000) - System.currentTimeMillis();
            }


            String parentField = null;
            String routingField = null;
            String type = typeSelector.getType(index, id);
            if(documentTypeParentFields != null && documentTypeParentFields.containsKey(type)) {
                parentField = documentTypeParentFields.get(type);
            }
            if(documentTypeRoutingFields != null && documentTypeRoutingFields.containsKey(type)) {
                routingField = documentTypeRoutingFields.get(type);
            }
            boolean deleted = meta.containsKey("deleted") ? (Boolean)meta.get("deleted") : false;

            if(deleted) {
                DeleteRequest deleteRequest = client.prepareDelete(index, type, id).request();
                bulkBuilder.add(deleteRequest);
            } else {
                IndexRequestBuilder indexBuilder = client.prepareIndex(index, type, id);
                indexBuilder.setSource(toBeIndexed);
                if(ttl > 0) {
                    indexBuilder.setTTL(ttl);
                }
                if(parentField != null) {
                    Object parent = JSONMapPath(toBeIndexed, parentField);
                    if (parent != null && parent instanceof String ) {
                        indexBuilder.setParent((String)parent);
                    } else {
                        logger.warn("Unabled to determine parent value from parent field {} for doc id {}", parentField, id);
                    }
                }
                if(routingField != null) {
                    Object routing = JSONMapPath(toBeIndexed, routingField);
                    if (routing != null && routing instanceof String) {
                        indexBuilder.setRouting((String)routing);
                    } else {
                        logger.warn("Unable to determine routing value from routing field {} for doc id {}", routingField, id);
                    }
                }
                IndexRequest indexRequest = indexBuilder.request();
                bulkBuilder.add(indexRequest);
            }
        }

        List<Object> result = null;

        long retriesLeft = this.bulkIndexRetries;
        int attempt = 0;

        BulkResponse response = null;
        do {
            attempt++;
            result = new ArrayList<Object>();
            if(response != null) {
                // at least second time through
                try {
                    Thread.sleep(this.bulkIndexRetryWaitMs);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
            response = bulkBuilder.execute().actionGet();
            if(response != null) {
                for (BulkItemResponse bulkItemResponse : response.getItems()) {
                    if(!bulkItemResponse.isFailed()) {
                        String itemId = bulkItemResponse.getId();
                        String itemRev = revisions.get(itemId);
                        Map<String, Object> itemResponse = new HashMap<String, Object>();
                        itemResponse.put("id", itemId);
                        itemResponse.put("rev", itemRev);
                        result.add(itemResponse);
                    } else {
                        Failure failure = bulkItemResponse.getFailure();
                        // if the error is fatal don't retry
                        if(failureMessageAppearsFatal(failure.getMessage())) {
                            throw new RuntimeException("indexing error " + failure.getMessage());
                        }
                    }
                }
            }
            retriesLeft--;
        } while((response != null) && (response.hasFailures()) && (retriesLeft > 0));

        if(response == null) {
            throw new RuntimeException("indexing error, bulk response was null");
        }

        if(retriesLeft == 0) {
            throw new RuntimeException("indexing error, bulk failed after all retries");
        }

        logger.debug("bulk index succeeded after {} tries", attempt);

        long end = System.currentTimeMillis();
        meanBulkDocsRequests.inc(end - start);
        activeBulkDocsRequests.dec();
        return result;
    }

    public boolean failureMessageAppearsFatal(String failureMessage) {
        if(failureMessage.contains("EsRejectedExecutionException")) {
            return false;
        }
        return true;
    }

    @Override
    public Map<String, Object> getDocument(String database, String docId) {
        String index = getElasticSearchIndexNameFromDatabase(database);
        String type = typeSelector.getType(index, docId);
        return getDocumentElasticSearch(getElasticSearchIndexNameFromDatabase(database), docId, type);
    }

    @Override
    public Map<String, Object> getLocalDocument(String database, String docId) {
        return getDocumentElasticSearch(getElasticSearchIndexNameFromDatabase(database), docId, checkpointDocumentType);
    }

    protected Map<String, Object> getDocumentElasticSearch(String index, String docId, String docType) {
        GetResponse response = client.prepareGet(index, docType, docId).execute().actionGet();
        if(response != null && response.isExists()) {
            Map<String,Object> esDocument = response.getSourceAsMap();
            return (Map<String, Object>)esDocument.get("doc");
        }
        return null;
    }

    @Override
    public String storeDocument(String database, String docId, Map<String, Object> document) {
        String index = getElasticSearchIndexNameFromDatabase(database);
        String type = typeSelector.getType(index, docId);
        return storeDocumentElasticSearch(getElasticSearchIndexNameFromDatabase(database), docId, document, type);
    }

    @Override
    public String storeLocalDocument(String database, String docId,
            Map<String, Object> document) {
        return storeDocumentElasticSearch(getElasticSearchIndexNameFromDatabase(database), docId, document, checkpointDocumentType);
    }

    protected String storeDocumentElasticSearch(String index, String docId, Map<String, Object> document, String docType) {
        // normally we just use the revision number present in the document
        String documentRevision = (String)document.get("_rev");
        if(documentRevision == null) {
            // if there isn't one we need to generate a revision number
            documentRevision = generateRevisionNumber();
            document.put("_rev", documentRevision);
        }
        IndexRequestBuilder indexBuilder = client.prepareIndex(index, docType, docId);
        indexBuilder.setSource(document);
        IndexResponse response = indexBuilder.execute().actionGet();
        if(response != null) {
            return documentRevision;
        }
        return null;
    }

    protected String generateRevisionNumber() {
        String documentRevision = "1-" + UUID.randomUUID().toString();
        return documentRevision;
    }

    @Override
    public InputStream getAttachment(String database, String docId,
            String attachmentName) {
        throw new UnsupportedOperationException("Attachments are not supported");
    }

    @Override
    public String storeAttachment(String database, String docId,
            String attachmentName, String contentType, InputStream input) {
        throw new UnsupportedOperationException("Attachments are not supported");
    }

    @Override
    public InputStream getLocalAttachment(String databsae, String docId,
            String attachmentName) {
        throw new UnsupportedOperationException("Attachments are not supported");
    }

    @Override
    public String storeLocalAttachment(String database, String docId,
            String attachmentName, String contentType, InputStream input) {
        throw new UnsupportedOperationException("Attachments are not supported");
    }

    protected String getElasticSearchIndexNameFromDatabase(String database) {
        String[] pieces = database.split("/", 2);
        if(pieces.length < 2) {
            return database;
        } else {
            return pieces[0];
        }
    }

    protected String getBucketUUIDFromDatabase(String database) {
        String[] pieces = database.split(";", 2);
        if(pieces.length < 2) {
            return null;
        } else {
            return pieces[1];
        }
    }

    protected String getDatabaseNameWithoutUUID(String database) {
        int semicolonIndex = database.indexOf(';');
        if(semicolonIndex >= 0) {
            return database.substring(0, semicolonIndex);
        }
        return database;
    }

    public Map<String, Object> getStats() {
        Map<String, Object> stats = new HashMap<String, Object>();

        Map<String, Object> bulkDocsStats = new HashMap<String, Object>();
        bulkDocsStats.put("activeCount", activeBulkDocsRequests.count());
        bulkDocsStats.put("totalCount", meanBulkDocsRequests.count());
        bulkDocsStats.put("totalTime", meanBulkDocsRequests.sum());
        bulkDocsStats.put("avgTime", meanBulkDocsRequests.mean());

        Map<String, Object> revsDiffStats = new HashMap<String, Object>();
        revsDiffStats.put("activeCount", activeRevsDiffRequests.count());
        revsDiffStats.put("totalCount", meanRevsDiffRequests.count());
        revsDiffStats.put("totalTime", meanRevsDiffRequests.sum());
        revsDiffStats.put("avgTime", meanRevsDiffRequests.mean());

        stats.put("_bulk_docs", bulkDocsStats);
        stats.put("_revs_diff", revsDiffStats);
        stats.put("tooManyConcurrentRequestsErrors", totalTooManyConcurrentRequestsErrors.count());

        return stats;
    }

    protected String getUUIDFromCheckpointDocSource(Map<String, Object> source) {
        Map<String,Object> docMap = (Map<String,Object>)source.get("doc");
        String uuid = (String)docMap.get("uuid");
        return uuid;
    }

    protected String lookupUUID(String bucket, String id) {
        GetRequestBuilder builder = client.prepareGet();
        builder.setIndex(bucket);
        builder.setId(id);
        builder.setType(this.checkpointDocumentType);
        builder.setFetchSource(true);

        String bucketUUID = null;
        GetResponse response;
        ListenableActionFuture<GetResponse> laf = builder.execute();
        if(laf != null) {
            response = laf.actionGet();
            if(response.isExists()) {
            Map<String,Object> responseMap = response.getSourceAsMap();
            bucketUUID = this.getUUIDFromCheckpointDocSource(responseMap);
            }
        }

        return bucketUUID;
    }

    protected void storeUUID(String bucket, String id, String uuid) {
        Map<String,Object> doc = new HashMap<String, Object>();
        doc.put("uuid", uuid);
        Map<String, Object> toBeIndexed = new HashMap<String, Object>();
        toBeIndexed.put("doc", doc);

        IndexRequestBuilder builder = client.prepareIndex();
        builder.setIndex(bucket);
        builder.setId(id);
        builder.setType(this.checkpointDocumentType);
        builder.setSource(toBeIndexed);
        builder.setOpType(OpType.CREATE);

        IndexResponse response;
        ListenableActionFuture<IndexResponse> laf = builder.execute();
        if(laf != null) {
            response = laf.actionGet();
            if(!response.isCreated()) {
                logger.error("did not succeed creating uuid");
            }
        }
    }

    public String getVBucketUUID(String pool, String bucket, int vbucket) {
        IndicesExistsRequestBuilder existsBuilder = client.admin().indices().prepareExists(bucket);
        IndicesExistsResponse response = existsBuilder.execute().actionGet();
        if(response.isExists()) {
            int tries = 0;
            String key = String.format("vbucket%dUUID",vbucket);
            String bucketUUID = this.lookupUUID(bucket, key);
            while(bucketUUID == null && tries < 100) {
                logger.debug("vbucket {} UUID doesn't exist yet,  creaating", vbucket);
                String newUUID = UUID.randomUUID().toString().replace("-", "");
                storeUUID(bucket, key, newUUID);
                bucketUUID = this.lookupUUID(bucket, key);
                tries++;
            }

            if(bucketUUID == null) {
                throw new RuntimeException("failed to find/create bucket uuid after 100 tries");
            }

            return bucketUUID;
        }
        return null;
    }

    @Override
    public String getBucketUUID(String pool, String bucket) {
        // first look for bucket UUID in cache
        String bucketUUID = this.bucketUUIDCache.getIfPresent(bucket);
        if (bucketUUID != null) {
            logger.debug("found bucket UUID in cache");
            return bucketUUID;
        }

        logger.debug("bucket UUID not in cache, looking up");
        IndicesExistsRequestBuilder existsBuilder = client.admin().indices().prepareExists(bucket);
        IndicesExistsResponse response = existsBuilder.execute().actionGet();
        if(response.isExists()) {
            int tries = 0;
            bucketUUID = this.lookupUUID(bucket, "bucketUUID");
            while(bucketUUID == null && tries < 100) {
                logger.debug("bucket UUID doesn't exist yet, creaating, attempt: {}", tries+1);
                String newUUID = UUID.randomUUID().toString().replace("-", "");
                storeUUID(bucket, "bucketUUID", newUUID);
                bucketUUID = this.lookupUUID(bucket, "bucketUUID");
                tries++;
            }

            if(bucketUUID != null) {
                // store it in the cache
                bucketUUIDCache.put(bucket, bucketUUID);
                return bucketUUID;
            }
        }
        throw new RuntimeException("failed to find/create bucket uuid");
    }

    public Object JSONMapPath(Map<String, Object> json, String path) {
        int dotIndex = path.indexOf('.');
        if (dotIndex >= 0) {
            String pathThisLevel = path.substring(0,dotIndex);
            Object current = json.get(pathThisLevel);
            String pathRest = path.substring(dotIndex+1);
            if (pathRest.length() == 0) {
                return current;
            }
            else if(current instanceof Map && pathRest.length() > 0) {
                return JSONMapPath((Map<String, Object>)current, pathRest);
            }
        } else {
            // no dot
            Object current = json.get(path);
            return current;
        }
        return null;
    }
}

/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.atlas.repository.impexp;

import org.apache.atlas.ApplicationProperties;
import org.apache.atlas.AtlasConstants;
import org.apache.atlas.AtlasException;
import org.apache.atlas.exception.AtlasBaseException;
import org.apache.atlas.model.impexp.AtlasServer;
import org.apache.atlas.model.impexp.AtlasExportRequest;
import org.apache.atlas.model.impexp.AtlasExportResult;
import org.apache.atlas.model.impexp.AtlasImportRequest;
import org.apache.atlas.model.impexp.AtlasImportResult;
import org.apache.atlas.model.impexp.ExportImportAuditEntry;
import org.apache.atlas.repository.Constants;
import org.apache.atlas.type.AtlasType;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import javax.inject.Inject;
import java.util.List;
import java.util.Map;

@Component
public class AuditsWriter {
    private static final Logger LOG = LoggerFactory.getLogger(AuditsWriter.class);
    private static final String CLUSTER_NAME_DEFAULT = "default";

    private AtlasServerService atlasServerService;
    private ExportImportAuditService auditService;

    private ExportAudits auditForExport = new ExportAudits();
    private ImportAudits auditForImport = new ImportAudits();

    @Inject
    public AuditsWriter(AtlasServerService atlasServerService, ExportImportAuditService auditService) {
        this.atlasServerService = atlasServerService;
        this.auditService = auditService;
    }

    public void write(String userName, AtlasExportResult result,
                      long startTime, long endTime,
                      List<String> entityCreationOrder) throws AtlasBaseException {
        auditForExport.add(userName, result, startTime, endTime, entityCreationOrder);
    }

    public void write(String userName, AtlasImportResult result,
                      long startTime, long endTime,
                      List<String> entityCreationOrder) throws AtlasBaseException {
        auditForImport.add(userName, result, startTime, endTime, entityCreationOrder);
    }

    private boolean isReplicationOptionSet(Map<String, ? extends Object> options, String replicatedKey) {
        return options.containsKey(replicatedKey);
    }

    private void updateReplicationAttribute(boolean isReplicationSet,
                                            String serverName,
                                            List<String> exportedGuids,
                                            String attrNameReplicated,
                                            long lastModifiedTimestamp) throws AtlasBaseException {
        if (!isReplicationSet || CollectionUtils.isEmpty(exportedGuids)) {
            return;
        }

        AtlasServer server = saveServer(serverName, exportedGuids.get(0), lastModifiedTimestamp);
        atlasServerService.updateEntitiesWithServer(server, exportedGuids, attrNameReplicated);
    }

    private String getClusterNameFromOptions(Map options, String key) {
        return options.containsKey(key)
                ? (String) options.get(key)
                : StringUtils.EMPTY;
    }

    private AtlasServer saveServer(String name) throws AtlasBaseException {
        return atlasServerService.save(new AtlasServer(name, name));
    }

    private AtlasServer saveServer(String name,
                                   String entityGuid,
                                   long lastModifiedTimestamp) throws AtlasBaseException {

        AtlasServer server = new AtlasServer(name, name);
        server.setAdditionalInfoRepl(entityGuid, lastModifiedTimestamp);

        if (LOG.isDebugEnabled()) {
            LOG.debug("saveServer: {}", server);
        }

        return atlasServerService.save(server);
    }

    public static String getCurrentClusterName() {
        try {
            return ApplicationProperties.get().getString(AtlasConstants.CLUSTER_NAME_KEY, CLUSTER_NAME_DEFAULT);
        } catch (AtlasException e) {
            LOG.error("getCurrentClusterName", e);
        }

        return StringUtils.EMPTY;
    }

    private class ExportAudits {
        private AtlasExportRequest request;
        private String targetServerName;
        private String optionKeyReplicatedTo;
        private boolean replicationOptionState;

        public void add(String userName, AtlasExportResult result,
                        long startTime, long endTime,
                        List<String> entityGuids) throws AtlasBaseException {
            optionKeyReplicatedTo = AtlasExportRequest.OPTION_KEY_REPLICATED_TO;
            request = result.getRequest();
            replicationOptionState = isReplicationOptionSet(request.getOptions(), optionKeyReplicatedTo);

            saveServers();

            auditService.add(userName, getCurrentClusterName(), targetServerName,
                    ExportImportAuditEntry.OPERATION_EXPORT,
                    AtlasType.toJson(result), startTime, endTime, !entityGuids.isEmpty());

            if (result.getOperationStatus() == AtlasExportResult.OperationStatus.FAIL) {
                return;
            }

            updateReplicationAttribute(replicationOptionState, targetServerName,
                    entityGuids, Constants.ATTR_NAME_REPLICATED_TO, result.getLastModifiedTimestamp());
        }

        private void saveServers() throws AtlasBaseException {
            saveServer(getCurrentClusterName());

            targetServerName = getClusterNameFromOptions(request.getOptions(), optionKeyReplicatedTo);
            if(StringUtils.isNotEmpty(targetServerName)) {
                saveServer(targetServerName);
            }
        }
    }

    private class ImportAudits {
        private AtlasImportRequest request;
        private boolean replicationOptionState;
        private String sourceServerName;
        private String optionKeyReplicatedFrom;

        public void add(String userName, AtlasImportResult result,
                        long startTime, long endTime,
                        List<String> entityGuids) throws AtlasBaseException {
            optionKeyReplicatedFrom = AtlasImportRequest.OPTION_KEY_REPLICATED_FROM;
            request = result.getRequest();
            replicationOptionState = isReplicationOptionSet(request.getOptions(), optionKeyReplicatedFrom);

            saveServers();

            auditService.add(userName,
                    sourceServerName, getCurrentClusterName(),
                    ExportImportAuditEntry.OPERATION_IMPORT,
                    AtlasType.toJson(result), startTime, endTime, !entityGuids.isEmpty());

            if(result.getOperationStatus() == AtlasImportResult.OperationStatus.FAIL) {
                return;
            }

            updateReplicationAttribute(replicationOptionState, this.sourceServerName, entityGuids,
                    Constants.ATTR_NAME_REPLICATED_FROM, result.getExportResult().getLastModifiedTimestamp());
        }

        private void saveServers() throws AtlasBaseException {
            saveServer(getCurrentClusterName());

            sourceServerName = getClusterNameFromOptionsState();
            if(StringUtils.isNotEmpty(sourceServerName)) {
                saveServer(sourceServerName);
            }
        }

        private String getClusterNameFromOptionsState() {
            return replicationOptionState
                    ? getClusterNameFromOptions(request.getOptions(), optionKeyReplicatedFrom)
                    : StringUtils.EMPTY;
        }
    }
}

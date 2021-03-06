/*
 * *** BEGIN LICENSE BLOCK *****
 * Version: MPL 1.1/GPL 2.0/LGPL 2.1
 *
 * The contents of this file are subject to the Mozilla Public License Version
 * 1.1 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 * http://www.mozilla.org/MPL/
 *
 * Software distributed under the License is distributed on an "AS IS" basis,
 * WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
 * for the specific language governing rights and limitations under the
 * License.
 *
 * The Original Code is part of dcm4che, an implementation of DICOM(TM) in
 * Java(TM), hosted at https://github.com/gunterze/dcm4che.
 *
 * The Initial Developer of the Original Code is
 * J4Care.
 * Portions created by the Initial Developer are Copyright (C) 2015
 * the Initial Developer. All Rights Reserved.
 *
 * Contributor(s):
 * See @authors listed below
 *
 * Alternatively, the contents of this file may be used under the terms of
 * either the GNU General Public License Version 2 or later (the "GPL"), or
 * the GNU Lesser General Public License Version 2.1 or later (the "LGPL"),
 * in which case the provisions of the GPL or the LGPL are applicable instead
 * of those above. If you wish to allow use of your version of this file only
 * under the terms of either the GPL or the LGPL, and not to allow others to
 * use your version of this file under the terms of the MPL, indicate your
 * decision by deleting the provisions above and replace them with the notice
 * and other provisions required by the GPL or the LGPL. If you do not delete
 * the provisions above, a recipient may use your version of this file under
 * the terms of any one of the MPL, the GPL or the LGPL.
 *
 * *** END LICENSE BLOCK *****
 */

package org.dcm4chee.arc.retrieve.scp;

import org.dcm4che3.conf.api.ConfigurationException;
import org.dcm4che3.conf.api.ConfigurationNotFoundException;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.VR;
import org.dcm4che3.net.Association;
import org.dcm4che3.net.QueryOption;
import org.dcm4che3.net.Status;
import org.dcm4che3.net.pdu.PresentationContext;
import org.dcm4che3.net.service.BasicCMoveSCP;
import org.dcm4che3.net.service.DicomServiceException;
import org.dcm4che3.net.service.QueryRetrieveLevel2;
import org.dcm4che3.net.service.RetrieveTask;
import org.dcm4chee.arc.conf.ArchiveAEExtension;
import org.dcm4chee.arc.retrieve.*;
import org.dcm4chee.arc.retrieve.scu.CMoveSCU;
import org.dcm4chee.arc.store.scu.CStoreSCU;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.util.*;

/**
 * @author Gunter Zeilinger <gunterze@gmail.com>
 * @since Aug 2015
 */
class CommonCMoveSCP extends BasicCMoveSCP {

    private static final Logger LOG = LoggerFactory.getLogger(CommonCMoveSCP.class);

    private final EnumSet<QueryRetrieveLevel2> qrLevels;

    @Inject
    private RetrieveService retrieveService;

    @Inject
    private CStoreSCU storeSCU;

    @Inject
    private CMoveSCU moveSCU;

    public CommonCMoveSCP(String sopClass, EnumSet<QueryRetrieveLevel2> qrLevels) {
        super(sopClass);
        this.qrLevels = qrLevels;
    }

    @Override
    protected RetrieveTask calculateMatches(Association as, PresentationContext pc, Attributes rq, Attributes keys)
            throws DicomServiceException {
        EnumSet<QueryOption> queryOpts = as.getQueryOptionsFor(rq.getString(Tag.AffectedSOPClassUID));
        QueryRetrieveLevel2 qrLevel = QueryRetrieveLevel2.validateRetrieveIdentifier(
                keys, qrLevels, queryOpts.contains(QueryOption.RELATIONAL));
        RetrieveContext ctx = newRetrieveContext(as, rq, qrLevel, keys);
        ArchiveAEExtension arcAE = ctx.getArchiveAEExtension();
        String fallbackCMoveSCP = arcAE.fallbackCMoveSCP();
        String fallbackCMoveSCPDestination = arcAE.fallbackCMoveSCPDestination();
        if (!retrieveService.calculateMatches(ctx)) {
            if (fallbackCMoveSCP == null)
                return null;

            LOG.info("{}: No objects of study{} found - forward C-MOVE RQ to {}",
                    as, Arrays.toString(ctx.getStudyInstanceUIDs()), fallbackCMoveSCP);
            return fallbackCMoveSCPDestination == null
                    ? moveSCU.newForwardRetrieveTask(ctx, pc, rq, keys, fallbackCMoveSCP)
                    : moveSCU.newForwardRetrieveTask(ctx, pc, rq, keys, fallbackCMoveSCP,
                        fallbackCMoveSCPDestination);
        }
        boolean retryFailedRetrieve = fallbackCMoveSCP != null && fallbackCMoveSCPDestination != null
                && retryFailedRetrieve(ctx, qrLevel);
        String altCMoveSCP = arcAE.alternativeCMoveSCP();
        if (altCMoveSCP != null) {
            Collection<InstanceLocations> notAccessable = retrieveService.removeNotAccessableMatches(ctx);
            if (!retryFailedRetrieve && ctx.getMatches().isEmpty()) {
                LOG.info("{}: No objects of study{} locally accessable - forward C-MOVE RQ to {}",
                        as, Arrays.toString(ctx.getStudyInstanceUIDs()), altCMoveSCP);
                return moveSCU.newForwardRetrieveTask(ctx, pc, rq, keys, altCMoveSCP);
            }

            if (!notAccessable.isEmpty()) {
                Set<SeriesKey> localSeries = seriesOf(ctx.getMatches());
                Map<SeriesKey, Collection<String>> remoteSeries = instancesBySeriesOf(notAccessable);
                LOG.info("{}: {} objects of study{} not locally accessable - send {} C-MOVE RQs to {}",
                        as, notAccessable.size(), Arrays.toString(ctx.getStudyInstanceUIDs()), remoteSeries.size(),
                        altCMoveSCP);
                try {
                    moveSCU.forwardMoveRQs(ctx, pc, rq, toKeys(remoteSeries, localSeries), altCMoveSCP);
                } catch (Exception e) {
                    for (InstanceLocations inst: notAccessable) {
                        ctx.incrementFailed();
                        ctx.addFailedSOPInstanceUID(inst.getSopInstanceUID());
                    }
                }
            }
        }
        if (retryFailedRetrieve) {
            LOG.info("{}: Some objects of study{} not found - retry forward C-MOVE RQ to {}",
                    as, Arrays.toString(ctx.getStudyInstanceUIDs()), fallbackCMoveSCP);
            if (ctx.getMatches().isEmpty())
                return moveSCU.newForwardRetrieveTask(ctx, pc, rq, keys, fallbackCMoveSCP, fallbackCMoveSCPDestination);

            moveSCU.forwardMoveRQs(ctx, pc, rq, keys, fallbackCMoveSCP, fallbackCMoveSCPDestination);
        }
        return storeSCU.newRetrieveTaskMOVE(as, pc, rq, ctx);
    }

    private Set<SeriesKey> seriesOf(Collection<InstanceLocations> instances) {
        Set<SeriesKey> series = new HashSet<>();
        for (InstanceLocations instance : instances)
            series.add(new SeriesKey(instance.getAttributes()));
        return series;
    }

    private Map<SeriesKey, Collection<String>> instancesBySeriesOf(Collection<InstanceLocations> instances) {
        Map<SeriesKey, Collection<String>> series = new HashMap<>();
        for (InstanceLocations instance : instances) {
            SeriesKey seriesKey = new SeriesKey(instance.getAttributes());
            Collection<String> iuids = series.get(seriesKey);
            if (iuids == null) {
                iuids = new ArrayList<>(instances.size());
                series.put(seriesKey, iuids);
            }
            iuids.add(instance.getSopInstanceUID());
        }
        return series;
    }

    private Attributes[] toKeys(Map<SeriesKey, Collection<String>> remoteSeries, Set<SeriesKey> localSeries) {
        Attributes[] keys = new Attributes[remoteSeries.size()];
        int i = 0;
        for (Map.Entry<SeriesKey, Collection<String>> entry : remoteSeries.entrySet()) {
            SeriesKey seriesKey = entry.getKey();
            keys[i++] = seriesKey.makeKeys(
                    localSeries.contains(seriesKey) ? QueryRetrieveLevel2.IMAGE : QueryRetrieveLevel2.SERIES,
                    entry.getValue());
        }
        return keys;
    }

    private static class SeriesKey {
        final String studyIUID;
        final String seriesIUID;

        SeriesKey(Attributes attrs) {
            studyIUID = attrs.getString(Tag.StudyInstanceUID);
            seriesIUID = attrs.getString(Tag.SeriesInstanceUID);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            SeriesKey seriesKey = (SeriesKey) o;

            if (!studyIUID.equals(seriesKey.studyIUID)) return false;
            return seriesIUID.equals(seriesKey.seriesIUID);

        }

        @Override
        public int hashCode() {
            int result = studyIUID.hashCode();
            result = 31 * result + seriesIUID.hashCode();
            return result;
        }

        public Attributes makeKeys(QueryRetrieveLevel2 qrLevel, Collection<String> iuids) {
            Attributes keys = new Attributes(4);
            keys.setString(Tag.QueryRetrieveLevel, VR.CS, qrLevel.toString());
            if (qrLevel == QueryRetrieveLevel2.IMAGE)
                keys.setString(Tag.SOPInstanceUID, VR.UI, iuids.toArray(new String[iuids.size()]));
            keys.setString(Tag.SeriesInstanceUID, VR.UI, seriesIUID);
            keys.setString(Tag.StudyInstanceUID, VR.UI, studyIUID);
            return keys;
        }
    }

    private RetrieveContext newRetrieveContext(Association as, Attributes rq, QueryRetrieveLevel2 qrLevel,
                                               Attributes keys) throws DicomServiceException {
        try {
            return retrieveService.newRetrieveContextMOVE(as, rq, qrLevel, keys);
        } catch (ConfigurationNotFoundException e) {
            throw new DicomServiceException(Status.MoveDestinationUnknown, e.getMessage());
        } catch (ConfigurationException e) {
            throw new DicomServiceException(Status.UnableToPerformSubOperations, e);
        }
    }

    private boolean retryFailedRetrieve(RetrieveContext ctx, QueryRetrieveLevel2 qrLevel) {
        HashSet<String> uids = new HashSet<>();
        ArchiveAEExtension arcae = ctx.getArchiveAEExtension();
        int maxRetrieveRetries = arcae.fallbackCMoveSCPRetries();
        switch (qrLevel) {
            case STUDY:
                uids.addAll(Arrays.asList(ctx.getStudyInstanceUIDs()));
                for (StudyInfo studyInfo : ctx.getStudyInfos()) {
                    if (studyInfo.getFailedSOPInstanceUIDList() != null) {
                        if (maxRetrieveRetries == 0 || studyInfo.getFailedRetrieves() < maxRetrieveRetries)
                            return true;
                        LOG.warn("{}: Maximal number of retries[{}] to retrieve objects of study[{}] from {} exceeded",
                                ctx.getRequestAssociation(), maxRetrieveRetries, studyInfo.getStudyInstanceUID(),
                                arcae.fallbackCMoveSCP());
                    }
                    uids.remove(studyInfo.getStudyInstanceUID());
                }
            case SERIES:
                uids.addAll(Arrays.asList(ctx.getSeriesInstanceUIDs()));
                for (SeriesInfo seriesInfo : ctx.getSeriesInfos()) {
                    if (seriesInfo.getFailedSOPInstanceUIDList() != null) {
                        if (maxRetrieveRetries == 0 || seriesInfo.getFailedRetrieves() < maxRetrieveRetries)
                            return true;
                        LOG.warn("{}: Maximal number of retries[{}] to retrieve objects of series[{}] from {} exceeded",
                                ctx.getRequestAssociation(), maxRetrieveRetries, seriesInfo.getSeriesInstanceUID(),
                                arcae.fallbackCMoveSCP());
                    }
                    uids.remove(seriesInfo.getSeriesInstanceUID());
                }
        }
        return !uids.isEmpty();
    }
}

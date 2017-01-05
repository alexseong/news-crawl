/**
 * Licensed to DigitalPebble Ltd under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * DigitalPebble licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.digitalpebble.stormcrawler.persistence;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.Map;

import org.slf4j.LoggerFactory;

import com.digitalpebble.stormcrawler.Constants;
import com.digitalpebble.stormcrawler.Metadata;
import com.digitalpebble.stormcrawler.persistence.DefaultScheduler;
import com.digitalpebble.stormcrawler.persistence.Status;
import com.digitalpebble.stormcrawler.protocol.HttpHeaders;
import com.digitalpebble.stormcrawler.util.ConfUtils;

/**
 * Adaptive fetch scheduler, checks by signature comparison whether a re-fetched
 * page has changed:
 * <ul>
 * <li>if yes, shrink the fetch interval up to a minimum fetch interval</li>
 * <li>if not, increase the fetch interval up to a maximum</li>
 * </ul>
 * 
 * <p>
 * The rate how the fetch interval is incremented or decremented is
 * configurable.
 * </p>
 * 
 * <p>
 * Note, that this scheduler requires the following metadata:
 * <dl>
 * <dt>signature</dt>
 * <dd>page signature, filled by MD5SignatureParseFilter</dd>
 * <dt>signatureOld</dt>
 * <dd>(temporary) copy of the previous signature, copied by
 * SignatureCopyParseFilter</dd>
 * </dl>
 * </p>
 * 
 */
public class AdaptiveScheduler extends DefaultScheduler {

    /**
     * Configuration property (boolean) whether or not to set the
     * &quot;last-modified&quot; metadata field when a page change was detected
     * by signature comparison.
     */
    public static final String SET_LAST_MODIFIED = "scheduler.adaptive.setLastModified";

    /**
     * Configuration property (int) to set the minimum fetch interval in
     * minutes.
     */
    public static final String INTERVAL_MIN = "scheduler.adaptive.fetchInterval.min";

    /**
     * Configuration property (int) to set the maximum fetch interval in
     * minutes.
     */
    public static final String INTERVAL_MAX = "scheduler.adaptive.fetchInterval.max";

    /**
     * Configuration property (float) to set the increment rate. If a page
     * hasn't changed when refetched, the fetch interval is multiplied by
     * (1.0 + incr_rate) until the max. fetch interval is reached.
     */
    public static final String INTERVAL_INC_RATE = "scheduler.adaptive.fetchInterval.rate.incr";

    /**
     * Configuration property (float) to set the decrement rate. If a page
     * has changed when refetched, the fetch interval is multiplied by
     * (1.0 - decr_rate). If the fetch interval comes closer to the
     * minimum interval, the decrementing is slowed down.
     */
    public static final String INTERVAL_DEC_RATE = "scheduler.adaptive.fetchInterval.rate.decr";

    /**
     * Name of the signature key in metadata, must be defined as
     * &quot;keyName&quot; in the configuration of
     * {@link com.digitalpebble.stormcrawler.parse.filter.MD5SignatureParseFilter}.
     * This key must be listed in &quot;metadata.persist&quot;.
     */
    public static final String SIGNATURE_KEY = "signature";

    /**
     * Name of key to hold previous signature: a copy, not overwritten by
     * {@link MD5SignatureParseFilter}, is added by
     * {@link com.digitalpebble.stormcrawler.parse.filter.SignatureCopyParseFilter}.
     * This key is a temporary copy, not persisted in metadata.
     */
    public static final String SIGNATURE_OLD_KEY = "signatureOld";

    /**
     * Key to store the current fetch interval value,
     * must be listed in &quot;metadata.persist&quot;.
     */
    public static final String FETCH_INTERVAL_KEY = "fetchInterval";

    /**
     * Key to store the date when the signature has been changed,
     * must be listed in &quot;metadata.persist&quot;.
     */
    public static final String SIGNATURE_MODIFIED_KEY = "signatureChangeDate";


    private static final org.slf4j.Logger LOG = LoggerFactory
            .getLogger(AdaptiveScheduler.class);

    protected int defaultfetchInterval;
    protected int minFetchInterval = 60;
    protected int maxFetchInterval = 60 * 24 * 14;
    protected float fetchIntervalDecRate = .5f;
    protected float fetchIntervalIncRate = .5f;

    protected boolean setLastModified = false;
    protected boolean overwriteLastModified = false;

    private SimpleDateFormat httpDateFormat = new SimpleDateFormat(
            "EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US);
    // TODO: httpDateFormat should be defined centrally

    @Override
    @SuppressWarnings({ "rawtypes", "unchecked" })
    public void init(Map stormConf) {
        defaultfetchInterval = ConfUtils.getInt(stormConf,
                Constants.defaultFetchIntervalParamName, 1440);
        setLastModified = ConfUtils.getBoolean(stormConf, SET_LAST_MODIFIED,
                false);
        minFetchInterval = ConfUtils.getInt(stormConf, INTERVAL_MIN,
                minFetchInterval);
        maxFetchInterval = ConfUtils.getInt(stormConf, INTERVAL_MAX,
                maxFetchInterval);
        fetchIntervalDecRate = ConfUtils.getFloat(stormConf, INTERVAL_DEC_RATE,
                fetchIntervalDecRate);
        fetchIntervalIncRate = ConfUtils.getFloat(stormConf, INTERVAL_INC_RATE,
                fetchIntervalIncRate);
        super.init(stormConf);
    }

    @Override
    public Date schedule(Status status, Metadata metadata) {
        LOG.debug("Scheduling status: {}, metadata: {}", status, metadata);

        String signature = metadata.getFirstValue(SIGNATURE_KEY);
        String oldSignature = metadata.getFirstValue(SIGNATURE_OLD_KEY); 

        if (status != Status.FETCHED) {

            // reset all metadata
            metadata.remove(SIGNATURE_MODIFIED_KEY);
            metadata.remove(FETCH_INTERVAL_KEY);
            metadata.remove(SIGNATURE_KEY);
            metadata.remove(SIGNATURE_OLD_KEY);

            // fall-back to DefaultScheduler
            return super.schedule(status, metadata);
        }

        Calendar now = Calendar.getInstance(Locale.ROOT);

        String signatureModified = metadata
                .getFirstValue(SIGNATURE_MODIFIED_KEY);

        boolean changed = false;

        if (signature == null || oldSignature == null) {
            // no decision possible by signature comparison if
            // - document not parsed (intentionally or not) or
            // - signature not generated or
            // - old signature not copied

            if (metadata.getFirstValue("fetch.statusCode").equals("304")) {
                // HTTP 304 Not Modified
            } else {
                // fall-back to DefaultScheduler
                LOG.debug("No signature for FETCHED page: {}", metadata);
                return super.schedule(status, metadata);
            }

        } else if (signature.equals(oldSignature)) {
            // unchanged, remove old signature (do not keep same signature twice)
            metadata.remove(SIGNATURE_OLD_KEY);
            if (signatureModified == null)
                signatureModified = httpDateFormat.format(now.getTime());
        } else {
            // change detected by signature comparison
            changed = true;
            signatureModified = httpDateFormat.format(now.getTime());
            if (setLastModified)
                metadata.setValue(HttpHeaders.LAST_MODIFIED,
                        httpDateFormat.format(now.getTime()));
        }

        String fetchInterval = metadata.getFirstValue(FETCH_INTERVAL_KEY);
        int interval = defaultfetchInterval;
        if (fetchInterval != null) {
            interval = Integer.parseInt(fetchInterval);
        } else {
            // initialize from DefaultScheduler
            // TODO: DefaultScheduler.checkMetadata() should not be private
            // interval = super.checkMetadata(metadata);
            Date nextFetch = super.schedule(status, metadata);
            interval = (int) (nextFetch.getTime() - now.getTime().getTime()) / 60000;
            fetchInterval = Integer.toString(interval);
        }

        if (changed) {
            // shrink fetch interval (slow down decrementing if already close to
            // the minimum interval)
            interval = (int) ((1.0f - fetchIntervalDecRate) * interval
                    + fetchIntervalDecRate * minFetchInterval);
            LOG.debug("Signature has changed, fetchInterval decreased from {} to {}",
                    fetchInterval, interval);

        } else {
            // no change or not modified, increase fetch interval
            interval = (int) (interval * (1.0f + fetchIntervalIncRate));
            if (interval > maxFetchInterval)
                interval = maxFetchInterval;
            LOG.debug(
                    "Unchanged, fetchInterval increased from {} to {}",
                    fetchInterval, interval);
        }

        metadata.setValue(FETCH_INTERVAL_KEY, Integer.toString(interval));
        metadata.setValue(SIGNATURE_MODIFIED_KEY, signatureModified);

        now.add(Calendar.MINUTE, interval);

        return now.getTime();
    }

}
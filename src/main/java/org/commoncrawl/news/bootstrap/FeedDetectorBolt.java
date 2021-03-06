package org.commoncrawl.news.bootstrap;

import org.apache.storm.tuple.Tuple;
import org.apache.storm.tuple.Values;
import org.slf4j.LoggerFactory;

import com.digitalpebble.stormcrawler.Constants;
import com.digitalpebble.stormcrawler.Metadata;
import com.digitalpebble.stormcrawler.bolt.FeedParserBolt;
import com.digitalpebble.stormcrawler.persistence.Status;
import com.digitalpebble.stormcrawler.protocol.HttpHeaders;

/** Detect RSS and Atom feeds, but do not parse and extract links */
public class FeedDetectorBolt extends FeedParserBolt {

    private static final org.slf4j.Logger LOG = LoggerFactory
            .getLogger(FeedDetectorBolt.class);

    public static final String[] mimeTypeClues = {
            "rss+xml", "atom+xml", "text/rss"
    };

    public static String[][] contentClues = { { "<rss" }, { "<feed" },
            { "http://www.w3.org/2005/Atom" } };
    protected static final int maxOffsetContentGuess = 512;
    private static ContentDetector contentDetector = new ContentDetector(
            contentClues, maxOffsetContentGuess);


    @Override
    public void execute(Tuple tuple) {
        Metadata metadata = (Metadata) tuple.getValueByField("metadata");

        byte[] content = tuple.getBinaryByField("content");
        String url = tuple.getStringByField("url");

        boolean isFeed = Boolean.valueOf(metadata.getFirstValue(isFeedKey));

        if (!isFeed) {
            String ct = metadata.getFirstValue(HttpHeaders.CONTENT_TYPE);
            if (ct != null) {
                for (String clue : mimeTypeClues) {
                    if (ct.contains(clue)) {
                        isFeed = true;
                        metadata.setValue(isFeedKey, "true");
                        LOG.info("Feed detected from content type <{}> for {}",
                                ct, url);
                        break;
                    }
                }
            }
        }

        if (!isFeed) {
            if (contentDetector.matches(content)) {
                isFeed = true;
                metadata.setValue(isFeedKey, "true");
                LOG.info("Feed detected from content: {}", url);
            }
        }

        if (isFeed) {
            // emit status
            collector.emit(Constants.StatusStreamName, tuple,
                    new Values(url, metadata, Status.FETCHED));
        } else {
            // pass on
            collector.emit(tuple, tuple.getValues());
        }
        collector.ack(tuple);
    }

}

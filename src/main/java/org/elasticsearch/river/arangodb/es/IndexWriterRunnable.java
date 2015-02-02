package org.elasticsearch.river.arangodb.es;

import java.io.Closeable;
import java.io.IOException;

import net.swisstech.arangodb.model.wal.WalEvent;

import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.inject.Singleton;
import org.elasticsearch.river.arangodb.EventStream;
import org.elasticsearch.river.arangodb.config.ArangoDbConfig;
import org.elasticsearch.river.arangodb.es.tick.Tick;

@Singleton
public class IndexWriterRunnable implements Runnable, Closeable {

	private final EventStream stream;
	private final int indexBulkSize;
	private final EsBulk bulk;

	private boolean keepRunning = true;


	@Inject
	public IndexWriterRunnable(ArangoDbConfig config, EsBulk bulk) {
		indexBulkSize = config.getIndexBulkSize();
		stream = config.getEventStream();
		this.bulk = bulk;
	}

	@Override
	public void run() {

		while (keepRunning) {

			long lastTickReceived = 0;
			WalEvent event = null;

			// read from the WAL's event stream and add them to the bulk request
			// up to the maximum configured request size
			while ((event = nextEvent()) != null) {
				lastTickReceived = bulk.add(event);

				// request is large enough, submit now
				if (bulk.size() > indexBulkSize) {
					break;
				}
			}

			// nothing to do, go back to reading from the stream, which will
			// block for at most 'indexBukTimeout' so no need to add a sleep here.
			if (bulk.size() < 1) {
				continue;
			}

			// append another request to write the value of the last tick received
			// to ElasticSearch for tracking and later reading if we're restarted
			bulk.add(new Tick(lastTickReceived));

			// execute the request
			BulkResponse resp = bulk.executeBulk();
			// TODO process/analyze response
		}
	}

	private WalEvent nextEvent() {
		try {
			return stream.poll();
		}
		catch (InterruptedException e) {
			// TODO Thread.currentThread().interrupt(); ???
			return null;
		}
	}

	@Override
	public void close() throws IOException {
		keepRunning = false;
	}
}
package org.elasticsearch.river.arangodb;

import static ch.bind.philib.lang.ThreadUtil.interruptAndJoin;

import java.util.concurrent.ThreadFactory;

import net.swisstech.swissarmyknife.io.Closeables;
import net.swisstech.swissarmyknife.lang.Threads;

import org.elasticsearch.ExceptionsHelper;
import org.elasticsearch.client.Client;
import org.elasticsearch.cluster.block.ClusterBlockException;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.inject.name.Named;
import org.elasticsearch.indices.IndexAlreadyExistsException;
import org.elasticsearch.river.AbstractRiverComponent;
import org.elasticsearch.river.River;
import org.elasticsearch.river.RiverName;
import org.elasticsearch.river.RiverSettings;
import org.elasticsearch.script.ScriptService;

public class ArangoDbRiver extends AbstractRiverComponent implements River {

	private final Client client;
	private final ArangoDbConfig config;
	private final Slurper slurper;
	private final Indexer indexer;
	private final ThreadFactory slurperThreadFactory;
	private final ThreadFactory indexerThreadFactory;

	private Thread slurperThread;
	private Thread indexerThread;

	@Inject
	public ArangoDbRiver( //
	final RiverName riverName, //
		final RiverSettings settings, //
		final Client client, //
		final ScriptService scriptService, //
		final ArangoDbConfig config, //
		final Slurper slurper, //
		final Indexer indexer, //
		@Named("arangodb_river_slurper_threadfactory") final ThreadFactory slurperThreadFactory, //
		@Named("arangodb_river_indexer_threadfactory") final ThreadFactory indexerThreadFactory //
	) throws ArangoDbException {

		super(riverName, settings);

		this.client = client;
		this.config = config;
		this.slurper = slurper;
		this.indexer = indexer;
		this.slurperThreadFactory = slurperThreadFactory;
		this.indexerThreadFactory = indexerThreadFactory;

		logger.debug("Prefix: [{}] - name: [{}]", logger.getPrefix(), logger.getName());
		logger.debug("River settings: [{}]", settings.settings());
	}

	@Override
	public void start() {
		logger.info("using arangodb server(s): host [{}], port [{}]", config.getArangodbHost(), config.getArangodbPort());
		logger.info("starting arangodb stream. options: throttlesize [{}], db [{}], collection [{}], script [{}], indexing to [{}]/[{}]", //
			config.getIndexThrottleSize(), //
			config.getArangodbDatabase(), //
			config.getArangodbCollection(), //
			config.getArangodbScript(), //
			config.getIndexName(), //
			config.getIndexType() //
			);

		try {
			client.admin().indices().prepareCreate(config.getIndexName()).execute().actionGet();
		}
		catch (Exception e) {
			if (ExceptionsHelper.unwrapCause(e) instanceof IndexAlreadyExistsException) {
				logger.info("index [{}] already exists", e, config.getIndexName());
			}
			else if (ExceptionsHelper.unwrapCause(e) instanceof ClusterBlockException) {
				logger.info("cluster block exception for index [{}]", e, config.getIndexName());
			}
			else {
				logger.error("failed to create index [{}], disabling river...", e, config.getIndexName());
				// TODO: shouldn't we throw some exception to let ES know an error has happened?
				return;
			}
		}

		slurperThread = slurperThreadFactory.newThread(slurper);
		slurperThread.start();

		indexerThread = indexerThreadFactory.newThread(indexer);
		indexerThread.start();

		logger.info("started arangodb river");
	}

	@Override
	public void close() {
		logger.info("closing arangodb stream river");

		Closeables.close(slurper);
		Closeables.close(indexer);

		Threads.sleepFor(100);

		interruptAndJoin(slurperThread, 50);
		interruptAndJoin(indexerThread, 50);
	}
}
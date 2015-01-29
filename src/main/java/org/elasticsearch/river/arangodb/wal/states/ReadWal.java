package org.elasticsearch.river.arangodb.wal.states;

import static org.elasticsearch.river.arangodb.wal.StateName.COLLECTION_MISSING;
import static org.elasticsearch.river.arangodb.wal.StateName.ENQUEUE;
import static org.elasticsearch.river.arangodb.wal.StateName.READ_WAL;
import static org.elasticsearch.river.arangodb.wal.StateName.SLEEP;

import java.io.IOException;

import net.swisstech.arangodb.WalClient;
import net.swisstech.arangodb.model.wal.WalDump;

import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.inject.Singleton;
import org.elasticsearch.river.arangodb.config.ArangoDbConfig;
import org.elasticsearch.river.arangodb.wal.BaseState;
import org.elasticsearch.river.arangodb.wal.StateMachine;

@Singleton
public class ReadWal extends BaseState {

	private final WalClient client;

	private long lastTick = 0;

	@Inject
	public ReadWal(StateMachine stateMachine, ArangoDbConfig config, WalClient client) {
		super(stateMachine, config, READ_WAL);
		this.client = client;
	}

	@Override
	public void execute() {

		/*
		 * do work
		 */

		String collName = config.getArangodbCollection();
		WalDump dump = null;
		int code = 0;
		try {
			dump = client.dump(collName, lastTick);
			code = dump.getResponseCode();
		}
		catch (IOException e) {
			e.printStackTrace();
		}

		/*
		 * next state
		 */


		if (dump == null) {
			// this is bad! maybe just a temporary network error?
			Sleep sleep = (Sleep) stateMachine.get(SLEEP);

			sleep.increaseErrorCount();
			stateMachine.push(sleep);
		}
		else if (200 == code) {
			Sleep sleep = (Sleep) stateMachine.get(SLEEP);
			Enqueue enqueue = (Enqueue) stateMachine.get(ENQUEUE);

			lastTick = dump.getHeaders().getReplicationLastincluded();
			sleep.resetErrorCount();
			enqueue.setData(dump);
			stateMachine.pop();
			stateMachine.push(enqueue);
		}
		else if (204 == code) {
			Sleep sleep = (Sleep) stateMachine.get(SLEEP);

			if (dump.getHeaders().getReplicationCheckmore()) {
				// no op, go straight back to reading the WAL
			}
			else {
				sleep.increaseErrorCount();
				stateMachine.push(sleep);
			}
		}
		else if (404 == code) {
			stateMachine.pop();
			stateMachine.push(COLLECTION_MISSING);
		}
	}

	public void setLastTick(long lastTick) {
		this.lastTick = lastTick;
	}
}

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Future;

import io.netty.buffer.ByteBuf;
import io.netty.channel.embedded.EmbeddedChannel;
import org.apache.sysds.runtime.controlprogram.federated.FederatedData;
import org.apache.sysds.conf.ConfigurationManager;
import org.apache.sysds.conf.DMLConfig;
import org.apache.sysds.runtime.controlprogram.federated.FederatedRequest;
import org.apache.sysds.runtime.controlprogram.federated.FederatedRequest.RequestType;
import org.apache.sysds.runtime.controlprogram.federated.FederatedResponse;
import org.apache.sysds.runtime.controlprogram.federated.FederatedResponse.ResponseType;
import org.apache.sysds.runtime.controlprogram.federated.FederatedWorker;
import org.apache.sysds.runtime.matrix.data.MatrixBlock;

/** Actual GET_VAR/PUT_VAR calibration over synthetic public dense blocks. */
public final class TransportCalibrationProbe {
	private static final int COLS = 128;
	private static final int[] BASE_ROWS = {64, 4096, 16384, 65536}; // 64 KiB, 4/16/64 MiB.
	private static final String[] DISTRIBUTIONS = {"fixed-total", "fixed-per-worker", "skew"};
	private static volatile double sink;
	private static long nextId = 56_000_000_000L;
	private static int warmup = 3;
	private static int repeats = 5;

	private TransportCalibrationProbe() { }

	private static final class Snapshot {
		final long rxBytes;
		final long txBytes;
		final long cpuUsec;

		Snapshot(long rxBytes, long txBytes, long cpuUsec) {
			this.rxBytes = rxBytes;
			this.txBytes = txBytes;
			this.cpuUsec = cpuUsec;
		}
	}

	private static long readLong(String path) throws IOException {
		return Long.parseLong(Files.readString(Path.of(path)).trim());
	}

	private static Snapshot snapshot() throws IOException {
		long usage = -1;
		for(String line : Files.readAllLines(Path.of("/sys/fs/cgroup/cpu.stat"))) {
			String[] fields = line.trim().split("\\s+");
			if(fields.length == 2 && fields[0].equals("usage_usec"))
				usage = Long.parseLong(fields[1]);
		}
		if(usage < 0)
			throw new IllegalStateException("cgroup v2 cpu.stat lacks usage_usec");
		return new Snapshot(readLong("/sys/class/net/eth0/statistics/rx_bytes"),
			readLong("/sys/class/net/eth0/statistics/tx_bytes"), usage);
	}

	private static MatrixBlock block(int rows, int worker) {
		MatrixBlock result = new MatrixBlock(rows, COLS, false);
		result.allocateDenseBlock();
		double[] data = result.getDenseBlockValues();
		for(int i = 0; i < data.length; i++) data[i] = (worker + 1) * 1048576d + (i % 8191) + 1;
		result.setNonZeros((long) rows * COLS);
		return result;
	}

	private static long logicalBytes(MatrixBlock block) {
		return (long) block.getNumRows() * block.getNumColumns() * Double.BYTES;
	}

	private static long responseEncodedBytes(MatrixBlock block) throws Exception {
		EmbeddedChannel encoder = new EmbeddedChannel(new FederatedWorker.FederatedResponseEncoder());
		try {
			encoder.writeOutbound(new FederatedResponse(ResponseType.SUCCESS, block));
			ByteBuf wire = encoder.readOutbound();
			try {
				return wire.readableBytes();
			}
			finally {
				wire.release();
			}
		}
		finally {
			encoder.finishAndReleaseAll();
		}
	}

	private static long requestEncodedBytes(long id, MatrixBlock block) throws Exception {
		EmbeddedChannel encoder = new EmbeddedChannel(new FederatedData.FederatedRequestEncoder());
		try {
			FederatedRequest[] request = {new FederatedRequest(RequestType.PUT_VAR, id, block)};
			encoder.writeOutbound((Object) request);
			ByteBuf wire = encoder.readOutbound();
			try {
				return wire.readableBytes();
			}
			finally {
				wire.release();
			}
		}
		finally {
			encoder.finishAndReleaseAll();
		}
	}

	private static FederatedResponse[] request(List<InetSocketAddress> sites,
		FederatedRequest[] requests) throws Exception {
		if(requests.length != 1 && requests.length != sites.size())
			throw new IllegalArgumentException("request cardinality must be one or worker count");
		List<Future<FederatedResponse>> futures = new ArrayList<>();
		for(int worker = 0; worker < sites.size(); worker++)
			futures.add(FederatedData.executeFederatedOperation(sites.get(worker),
				requests.length == 1 ? requests[0] : requests[worker]));
		FederatedResponse[] responses = new FederatedResponse[sites.size()];
		for(int worker = 0; worker < responses.length; worker++) {
			Future<FederatedResponse> future = futures.get(worker);
			responses[worker] = future.get();
			if(!responses[worker].isSuccessful())
				throw new IllegalStateException(responses[worker].getErrorMessage());
		}
		return responses;
	}

	private static FederatedResponse[] request(List<InetSocketAddress> sites,
		FederatedRequest request) throws Exception {
		return request(sites, new FederatedRequest[] {request});
	}

	private static int[] rowDistribution(int baseRows, int workers, String distribution) {
		int[] rows = new int[workers];
		if(distribution.equals("fixed-per-worker"))
			Arrays.fill(rows, baseRows);
		else if(distribution.equals("fixed-total")) {
			Arrays.fill(rows, baseRows / workers);
			for(int i = 0; i < baseRows % workers; i++)
				rows[i]++;
		}
		else if(distribution.equals("skew") && workers == 3) {
			rows[0] = Math.max(1, (int) Math.floor(baseRows * 0.70));
			rows[1] = Math.max(1, (int) Math.floor(baseRows * 0.15));
			rows[2] = baseRows - rows[0] - rows[1];
		}
		else
			throw new IllegalArgumentException("illegal distribution: " + distribution);
		return rows;
	}

	/** Allocate and install all GET variables before any measured retrieval. */
	private static long[] prepareGetVariables(List<InetSocketAddress> sites, MatrixBlock[] blocks)
		throws Exception {
		long[] ids = new long[sites.size()];
		FederatedRequest[] puts = new FederatedRequest[sites.size()];
		for(int worker = 0; worker < sites.size(); worker++) {
			ids[worker] = nextId++;
			puts[worker] = new FederatedRequest(RequestType.PUT_VAR, ids[worker], blocks[worker]);
		}
		request(sites, puts);
		return ids;
	}

	/** Allocate the complete PUT id schedule before entering its measured loop. */
	private static long[][] preparePutIds(int workers) {
		long[][] ids = new long[warmup + repeats][workers];
		for(int sample = 0; sample < ids.length; sample++)
			Arrays.fill(ids[sample], nextId++);
		return ids;
	}

	private static MatrixBlock[] blocks(int[] rows, boolean shared) {
		MatrixBlock[] result = new MatrixBlock[rows.length];
		if(shared) {
			MatrixBlock value = block(rows[0], 0);
			Arrays.fill(result, value);
		}
		else {
			for(int worker = 0; worker < rows.length; worker++)
				result[worker] = block(rows[worker], worker);
		}
		return result;
	}

	private static void verify(MatrixBlock actual, MatrixBlock expected, int worker) {
		if(actual.getNumRows() != expected.getNumRows() || actual.getNumColumns() != COLS
			|| !Arrays.equals(actual.getDenseBlockValues(), expected.getDenseBlockValues()))
			throw new IllegalStateException("transport payload mismatch for worker " + worker);
	}

	private static long assemble(FederatedResponse[] responses, MatrixBlock[] expected) throws Exception {
		int values = 0;
		for(MatrixBlock block : expected)
			values = Math.addExact(values, Math.multiplyExact(block.getNumRows(), COLS));
		double[] assembled = new double[values];
		int offset = 0;
		for(int worker = 0; worker < responses.length; worker++) {
			MatrixBlock actual = (MatrixBlock) responses[worker].getData()[0];
			double[] valuesForWorker = actual.getDenseBlockValues();
			System.arraycopy(valuesForWorker, 0, assembled, offset, valuesForWorker.length);
			offset += valuesForWorker.length;
		}
		sink = assembled[assembled.length - 1];
		return assembled.length;
	}

	private static long encodedResponseBytes(MatrixBlock[] blocks) throws Exception {
		long result = 0;
		for(MatrixBlock block : blocks)
			result += responseEncodedBytes(block);
		return result;
	}

	private static long encodedRequestBytes(long[] ids, MatrixBlock[] blocks) throws Exception {
		long result = 0;
		for(int worker = 0; worker < blocks.length; worker++)
			result += requestEncodedBytes(ids[worker], blocks[worker]);
		return result;
	}

	private static long totalLogicalBytes(MatrixBlock[] blocks) {
		long result = 0;
		for(MatrixBlock block : blocks)
			result += logicalBytes(block);
		return result;
	}

	private static void emit(String operation, String distribution, int payloadRows, int sample,
		long logicalBytes, long encodedBytes, long retrievalNanos, long assemblyNanos,
		Snapshot before, Snapshot after, boolean correctness) {
		System.out.printf(Locale.ROOT,
			"{\"kind\":\"transport-calibration\",\"operation\":\"%s\","
			+ "\"distribution\":\"%s\",\"payload_mib\":%.4f,\"workers\":%d,\"sample\":%d,"
			+ "\"logical_bytes\":%d,\"application_frame_bytes\":%d,\"elapsed_ns\":%d,"
			+ "\"retrieval_elapsed_ns\":%d,"
			+ "\"assembly_elapsed_ns\":%d,\"coordinator_rx_bytes_before\":%d,"
			+ "\"coordinator_rx_bytes_after\":%d,\"coordinator_tx_bytes_before\":%d,"
			+ "\"coordinator_tx_bytes_after\":%d,\"cgroup_cpu_usage_usec_before\":%d,"
			+ "\"cgroup_cpu_usage_usec_after\":%d,"
			+ "\"coefficient_scope\":\"effective_transport_with_systemds_rpc_codec_not_pure_codec\","
			+ "\"correctness\":%s}%n",
			operation, distribution, payloadRows * COLS * 8.0 / 1048576.0, expectedWorkers,
			sample, logicalBytes, encodedBytes, retrievalNanos, retrievalNanos, assemblyNanos,
			before.rxBytes, after.rxBytes, before.txBytes, after.txBytes,
			before.cpuUsec, after.cpuUsec, correctness ? "true" : "false");
	}

	private static int expectedWorkers;

	private static void clear(List<InetSocketAddress> sites) throws Exception {
		request(sites, new FederatedRequest(RequestType.CLEAR, -1));
	}

	private static void getCase(List<InetSocketAddress> sites, int baseRows, String distribution)
		throws Exception {
		int[] rows = rowDistribution(baseRows, sites.size(), distribution);
		MatrixBlock[] expected = blocks(rows, false);
		long[] ids = prepareGetVariables(sites, expected);
		long logical = totalLogicalBytes(expected);
		long encoded = encodedResponseBytes(expected);
		for(int iteration = -warmup; iteration < repeats; iteration++) {
			FederatedRequest[] gets = new FederatedRequest[sites.size()];
			for(int worker = 0; worker < sites.size(); worker++)
				gets[worker] = new FederatedRequest(RequestType.GET_VAR, ids[worker]);
			Snapshot before = snapshot();
			long start = System.nanoTime();
			FederatedResponse[] responses = request(sites, gets); // parallel issue, then unbounded waits.
			long retrieval = System.nanoTime() - start;
			Snapshot after = snapshot();
			start = System.nanoTime();
			long assembledValues = assemble(responses, expected);
			long assembly = System.nanoTime() - start;
			for(int worker = 0; worker < responses.length; worker++)
				verify((MatrixBlock)responses[worker].getData()[0], expected[worker], worker);
			if(assembledValues * Double.BYTES != logical)
				throw new IllegalStateException("assembled byte count mismatch");
			if(iteration >= 0)
				emit("get", distribution, baseRows, iteration, logical, encoded,
					retrieval, assembly, before, after, true);
		}
		clear(sites);
	}

	private static void putCase(List<InetSocketAddress> sites, int baseRows, String distribution,
		boolean broadcast) throws Exception {
		int[] rows = broadcast ? rowDistribution(baseRows, sites.size(), "fixed-per-worker")
			: rowDistribution(baseRows, sites.size(), distribution);
		MatrixBlock[] payloads = blocks(rows, broadcast);
		long[][] ids = preparePutIds(sites.size());
		long logical = totalLogicalBytes(payloads);
		long encoded = encodedRequestBytes(ids[0], payloads);
		for(int iteration = -warmup; iteration < repeats; iteration++) {
			int slot = iteration + warmup;
			FederatedRequest[] puts = new FederatedRequest[sites.size()];
			for(int worker = 0; worker < sites.size(); worker++)
				puts[worker] = new FederatedRequest(RequestType.PUT_VAR, ids[slot][worker], payloads[worker]);
			if(broadcast) Arrays.fill(puts, puts[0]);
			Snapshot before = snapshot();
			long start = System.nanoTime();
			request(sites, puts);
			long transfer = System.nanoTime() - start;
			Snapshot after = snapshot();
			// Correctness traffic is deliberately outside the timed PUT interval.
			FederatedRequest[] gets = new FederatedRequest[sites.size()];
			for(int worker = 0; worker < sites.size(); worker++)
				gets[worker] = new FederatedRequest(RequestType.GET_VAR, ids[slot][worker]);
			FederatedResponse[] responses = request(sites, gets);
			for(int worker = 0; worker < sites.size(); worker++)
				verify((MatrixBlock) responses[worker].getData()[0], payloads[worker], broadcast ? 0 : worker);
			if(iteration >= 0)
				emit(broadcast ? "broadcast-put" : "slice-put", distribution, baseRows, iteration,
					logical, encoded, transfer, 0, before, after, true);
		}
		clear(sites);
	}

	private static void run(List<InetSocketAddress> sites) throws Exception {
		for(int baseRows : BASE_ROWS) {
			for(String distribution : DISTRIBUTIONS) {
				if(distribution.equals("skew") && sites.size() != 3)
					continue;
				getCase(sites, baseRows, distribution);
			}
			putCase(sites, baseRows, "fixed-per-worker", true);
			for(String distribution : DISTRIBUTIONS) {
				if(distribution.equals("skew") && sites.size() != 3)
					continue;
				putCase(sites, baseRows, distribution, false);
			}
		}
	}

	public static void main(String[] args) throws Exception {
		String endpoints = "";
		String configPath = null;
		for(int i = 0; i < args.length; i++) {
			switch(args[i]) {
				case "--config": configPath = args[++i]; break;
				case "--sites": endpoints = args[++i]; break;
				case "--workers": expectedWorkers = Integer.parseInt(args[++i]); break;
				case "--warmup": warmup = Integer.parseInt(args[++i]); break;
				case "--repeats": repeats = Integer.parseInt(args[++i]); break;
				default: throw new IllegalArgumentException(args[i]);
			}
		}
		if(configPath == null) throw new IllegalArgumentException("explicit timeout-free config required");
		DMLConfig config = new DMLConfig(configPath);
		ConfigurationManager.setGlobalConfig(config);
		if(config.getIntValue(DMLConfig.FEDERATED_TIMEOUT) != -1)
			throw new IllegalArgumentException("federated timeout must be disabled");
		List<InetSocketAddress> sites = new ArrayList<>();
		for(String endpoint : endpoints.split(",")) {
			String[] parts = endpoint.split(":");
			sites.add(new InetSocketAddress(parts[0], Integer.parseInt(parts[1])));
		}
		if(sites.size() != expectedWorkers || (sites.size() != 1 && sites.size() != 3))
			throw new IllegalArgumentException("only exact W1/W3 topology is accepted");
		try {
			run(sites);
		}
		finally {
			FederatedData.clearWorkGroup();
			FederatedData.resetFederatedSites();
		}
	}
}

package fogids.sim;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.core.CloudSimTags;
import org.cloudbus.cloudsim.core.SimEvent;
import org.fog.application.AppLoop;
import org.fog.application.Application;
import org.fog.entities.Actuator;
import org.fog.entities.FogDevice;
import org.fog.entities.Sensor;
import org.fog.placement.Controller;
import org.fog.utils.FogEvents;
import org.fog.utils.NetworkUsageMonitor;
import org.fog.utils.TimeKeeper;

/**
 * Extends iFogSim2's Controller to:
 *  (1) periodically sample every fog device's compute utilisation and write
 *      a time-series trace — the "node telemetry interface" Member C's
 *      resource monitor and quarantine logic will read from in a later
 *      integration pass;
 *  (2) on STOP_SIMULATION, write end-of-run metrics (loop latency, energy,
 *      network usage, execution cost) to a CSV row instead of only printing
 *      to stdout.
 *
 * Every other event tag is delegated to the base Controller unchanged.
 *
 * NOTE ON THE EVENT SYSTEM: this fork of CloudSim/iFogSim2 types event tags
 * as CloudSimTags (a marker interface implemented by the FogEvents enum),
 * not as raw ints. A custom periodic event therefore needs its own small
 * CloudSimTags-implementing enum rather than an arbitrary int constant.
 *
 * SAMPLING STRATEGY (revised — see results/NOTES.md item 3 for the full
 * before/after): an earlier version of this class point-sampled
 * FogDevice.getLastUtilization() once every 50 ms — which happened to be
 * exactly Topology.SENSOR_PERIOD_MS. Sampling a periodic bursty signal at
 * its own period is textbook aliasing: every sample lands at the same
 * relative phase, so the trace read as a suspiciously clean alternating
 * 0%/100% pattern regardless of the actual duty cycle. Staggering sensor
 * phase (Topology.java) helped the underlying traffic but did not fix
 * this, because the aliasing was between the SAMPLER and the traffic
 * period, not purely a synchronisation problem at the source.
 *
 * The fix here is a genuine time-average: an internal fine-grained tick
 * (FINE_TICK_MS) accumulates each device's utilisation every 1 ms, and a
 * separate, coarser interval (REPORT_MS) flushes the mean since the last
 * flush to the trace. This is robust to any periodicity in the traffic —
 * it does not depend on picking a "lucky" interval that happens not to
 * alias, which would be a fragile fix disguised as a robust one.
 */
public class MetricsController extends Controller {

    private enum LocalTags implements CloudSimTags {
        FINE_TICK
    }

    /** Internal accumulation tick — fine enough to catch sub-period bursts. */
    private static final double FINE_TICK_MS = 1.0;
    /** How often the averaged utilisation is written to the trace. */
    private static final double REPORT_MS = 25.0;

    private final String runLabel;
    private final String scenario;
    private final long seed;
    private final String resultsCsvPath;
    private final String utilizationCsvPath;

    private final List<String> utilizationRows = new ArrayList<>();
    private final java.util.Map<String, Double> accumSum = new java.util.HashMap<>();
    private final java.util.Map<String, Integer> accumCount = new java.util.HashMap<>();
    private double lastReportTime = 0.0;

    public MetricsController(String name, List<FogDevice> fogDevices, List<Sensor> sensors, List<Actuator> actuators,
            String runLabel, String scenario, long seed, String resultsCsvPath, String utilizationCsvPath) {
        super(name, fogDevices, sensors, actuators);
        this.runLabel = runLabel;
        this.scenario = scenario;
        this.seed = seed;
        this.resultsCsvPath = resultsCsvPath;
        this.utilizationCsvPath = utilizationCsvPath;
    }

    @Override
    public void startEntity() {
        super.startEntity();
        send(getId(), FINE_TICK_MS, LocalTags.FINE_TICK);
    }

    @Override
    public void processEvent(SimEvent ev) {
        if (ev.getTag() == LocalTags.FINE_TICK) {
            accumulate();
            if (CloudSim.clock() - lastReportTime >= REPORT_MS) {
                flushAveragedSample();
            }
            if (CloudSim.clock() < org.fog.utils.Config.MAX_SIMULATION_TIME) {
                send(getId(), FINE_TICK_MS, LocalTags.FINE_TICK);
            }
            return;
        }
        if (ev.getTag() == FogEvents.STOP_SIMULATION) {
            CloudSim.stopSimulation();
            if (!accumCount.isEmpty() && accumCount.values().iterator().next() > 0) {
                flushAveragedSample(); // don't drop a partial final window
            }
            writeUtilizationTrace();
            writeResultsRow();
            printSummaryToConsole();
            System.exit(0);
            return;
        }
        super.processEvent(ev);
    }

    private void accumulate() {
        for (FogDevice device : getFogDevices()) {
            String name = device.getName();
            accumSum.merge(name, device.getLastUtilization(), Double::sum);
            accumCount.merge(name, 1, Integer::sum);
        }
    }

    private void flushAveragedSample() {
        double clock = CloudSim.clock();
        for (FogDevice device : getFogDevices()) {
            String name = device.getName();
            int count = accumCount.getOrDefault(name, 0);
            double avg = (count > 0) ? accumSum.get(name) / count : 0.0;
            utilizationRows.add(String.join(",",
                    csv(runLabel), csv(scenario), String.valueOf(seed),
                    csv(name), String.format("%.3f", clock),
                    String.format("%.6f", avg)));
        }
        accumSum.clear();
        accumCount.clear();
        lastReportTime = clock;
    }

    private void writeUtilizationTrace() {
        boolean writeHeader = !new java.io.File(utilizationCsvPath).exists();
        try (PrintWriter pw = new PrintWriter(new FileWriter(utilizationCsvPath, true))) {
            if (writeHeader) {
                pw.println("run_label,scenario,seed,device,sim_time_ms,utilization_fraction");
            }
            for (String row : utilizationRows) {
                pw.println(row);
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to write utilization trace", e);
        }
    }

    private double loopAverage(int loopId) {
        Double v = TimeKeeper.getInstance().getLoopIdToCurrentAverage().get(loopId);
        return v == null ? Double.NaN : v;
    }

    private String loopLabel(int loopId) {
        for (Application app : getApplications().values()) {
            for (AppLoop loop : app.getLoops()) {
                if (loop.getLoopId() == loopId) {
                    return String.join("->", loop.getModules());
                }
            }
        }
        return "loop-" + loopId;
    }

    private FogDevice getCloudDevice() {
        for (FogDevice d : getFogDevices()) {
            if (d.getName().equals("cloud")) return d;
        }
        return null;
    }

    private void writeResultsRow() {
        boolean writeHeader = !new java.io.File(resultsCsvPath).exists();
        long wallClockMs = Calendar.getInstance().getTimeInMillis() - TimeKeeper.getInstance().getSimulationStartTime();
        double networkUsage = NetworkUsageMonitor.getNetworkUsage() / org.fog.utils.Config.MAX_SIMULATION_TIME;
        FogDevice cloud = getCloudDevice();
        double cloudCost = cloud == null ? Double.NaN : cloud.getTotalCost();

        StringBuilder loopSummary = new StringBuilder();
        for (Integer loopId : TimeKeeper.getInstance().getLoopIdToTupleIds().keySet()) {
            if (loopSummary.length() > 0) loopSummary.append(" | ");
            loopSummary.append(loopLabel(loopId)).append("=").append(String.format("%.4f", loopAverage(loopId)));
        }

        try (PrintWriter pw = new PrintWriter(new FileWriter(resultsCsvPath, true))) {
            if (writeHeader) {
                pw.println("run_label,scenario,seed,wall_clock_ms,network_usage,cloud_execution_cost,loop_latencies,"
                        + "energy_cloud,energy_proxy,energy_fog1,energy_fog2,energy_fog3");
            }
            java.util.Map<String, Double> energyByName = new java.util.HashMap<>();
            for (FogDevice d : getFogDevices()) {
                energyByName.put(d.getName(), d.getEnergyConsumption());
            }
            pw.println(String.join(",",
                    csv(runLabel), csv(scenario), String.valueOf(seed),
                    String.valueOf(wallClockMs),
                    String.format("%.6f", networkUsage),
                    String.format("%.6f", cloudCost),
                    csv(loopSummary.toString()),
                    fmt(energyByName.get("cloud")),
                    fmt(energyByName.get("proxy")),
                    fmt(energyByName.get("fog-1")),
                    fmt(energyByName.get("fog-2")),
                    fmt(energyByName.get("fog-3"))));
        } catch (IOException e) {
            throw new RuntimeException("Failed to write results row", e);
        }
    }

    private void printSummaryToConsole() {
        System.out.println("=== fog-ids run complete: " + runLabel + " (" + scenario + ", seed=" + seed + ") ===");
        System.out.println("Results appended to: " + resultsCsvPath);
        System.out.println("Utilization trace appended to: " + utilizationCsvPath);
    }

    private static String fmt(Double v) {
        return v == null ? "" : String.format("%.6f", v);
    }

    private static String csv(String s) {
        if (s == null) return "";
        if (s.contains(",") || s.contains("\"")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }
}

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
 */
public class MetricsController extends Controller {

    private enum LocalTags implements CloudSimTags {
        SAMPLE_UTILIZATION
    }

    private static final double SAMPLE_INTERVAL_MS = 50.0;

    private final String runLabel;
    private final String scenario;
    private final long seed;
    private final String resultsCsvPath;
    private final String utilizationCsvPath;

    private final List<String> utilizationRows = new ArrayList<>();

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
        send(getId(), SAMPLE_INTERVAL_MS, LocalTags.SAMPLE_UTILIZATION);
    }

    @Override
    public void processEvent(SimEvent ev) {
        if (ev.getTag() == LocalTags.SAMPLE_UTILIZATION) {
            sampleUtilization();
            // Re-arm unless we're at/past the simulation horizon.
            if (CloudSim.clock() < org.fog.utils.Config.MAX_SIMULATION_TIME) {
                send(getId(), SAMPLE_INTERVAL_MS, LocalTags.SAMPLE_UTILIZATION);
            }
            return;
        }
        if (ev.getTag() == FogEvents.STOP_SIMULATION) {
            CloudSim.stopSimulation();
            writeUtilizationTrace();
            writeResultsRow();
            printSummaryToConsole();
            System.exit(0);
            return;
        }
        super.processEvent(ev);
    }

    private void sampleUtilization() {
        double clock = CloudSim.clock();
        for (FogDevice device : getFogDevices()) {
            utilizationRows.add(String.join(",",
                    csv(runLabel), csv(scenario), String.valueOf(seed),
                    csv(device.getName()), String.format("%.3f", clock),
                    String.format("%.6f", device.getLastUtilization())));
        }
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

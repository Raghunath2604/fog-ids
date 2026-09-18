package fogids.sim;

import java.util.Calendar;

import org.cloudbus.cloudsim.Log;
import org.cloudbus.cloudsim.core.CloudSim;
import org.fog.entities.FogBroker;
import org.fog.placement.Controller;
import org.fog.placement.ModuleMapping;
import org.fog.placement.ModulePlacement;
import org.fog.placement.ModulePlacementEdgewards;
import org.fog.placement.ModulePlacementMapping;
import org.fog.application.Application;
import org.fog.utils.Config;
import org.fog.utils.TimeKeeper;

/**
 * Entry point for one simulation run. Each run is a single JVM invocation —
 * intentional, since the base Controller's STOP_SIMULATION handling calls
 * System.exit(0), and keeping one process per run means a run's config,
 * seed, and output are simple command-line arguments with no shared-state
 * risk between runs. The shell harness in run/ loops over scenarios and
 * seeds by invoking this class repeatedly.
 *
 * Usage:
 *   java fogids.sim.RunScenario <scenario> <seed> <resultsCsv> <utilizationCsv> [maxSimTimeMs]
 *
 * <scenario> is one of: CLOUD_ONLY, FOG_NO_IDS, FOG_WITH_IDS
 * (see the architecture report's three comparison scenarios, Section "Simulation plan").
 */
public class RunScenario {

    public enum Scenario { CLOUD_ONLY, FOG_NO_IDS, FOG_WITH_IDS }

    public static void main(String[] args) throws Exception {
        if (args.length < 4) {
            System.err.println("Usage: RunScenario <CLOUD_ONLY|FOG_NO_IDS|FOG_WITH_IDS> <seed> <resultsCsv> <utilizationCsv> [maxSimTimeMs]");
            System.exit(2);
        }
        Scenario scenario = Scenario.valueOf(args[0]);
        long seed = Long.parseLong(args[1]);
        String resultsCsv = args[2];
        String utilizationCsv = args[3];
        int maxSimTimeMs = args.length > 4 ? Integer.parseInt(args[4]) : 5000;

        // Threaded through now for forward compatibility: no stochastic component
        // exists yet (sensor emission is a fixed DeterministicDistribution), so
        // varying the seed currently has no effect on output. It will matter once
        // Member C's attack injectors add randomised timing/targets.
        Rng.seed(seed);

        Config.MAX_SIMULATION_TIME = maxSimTimeMs;

        Log.disable();
        int numUser = 1;
        Calendar calendar = Calendar.getInstance();
        CloudSim.init(numUser, calendar, false);

        String appId = "fog-ids-" + scenario.name().toLowerCase();
        FogBroker broker = new FogBroker("broker");

        boolean includeIds = (scenario == Scenario.FOG_WITH_IDS);
        Application application = AppGraph.build(appId, broker.getId(), includeIds);
        application.setUserId(broker.getId());

        Topology topo = new Topology();
        topo.build(broker.getId(), appId);

        String runLabel = scenario.name() + "_seed" + seed;
        MetricsController controller = new MetricsController(
                "controller-" + runLabel, topo.fogDevices, topo.sensors, topo.actuators,
                runLabel, scenario.name(), seed, resultsCsv, utilizationCsv);

        // IMPORTANT: the placement algorithm (ModulePlacementEdgewards in particular)
        // walks parent/child links to find leaf-to-root paths from each fog device to
        // the cloud. Those links are only populated by Controller's constructor
        // (connectWithLatencies()). Placement must therefore be built AFTER the
        // controller exists, exactly as iFogSim2's own reference examples do it —
        // building it earlier silently produces a placement with every fog node
        // treated as isolated (edgewards then places nothing at the edge at all,
        // with no error raised).
        ModuleMapping moduleMapping = ModuleMapping.createModuleMapping();
        ModulePlacement placement;

        if (scenario == Scenario.CLOUD_ONLY) {
            // Traditional centralised baseline: every module runs in the cloud.
            // There is no edge presence at all, so there is no fast path either —
            // that is the point of this baseline, not an oversight.
            moduleMapping.addModuleToDevice(AppGraph.MOD_FASTPATH, "cloud");
            moduleMapping.addModuleToDevice(AppGraph.MOD_PROCESSING, "cloud");
            moduleMapping.addModuleToDevice(AppGraph.MOD_CLOUD_ANALYTICS, "cloud");
            placement = new ModulePlacementMapping(topo.fogDevices, application, moduleMapping);
        } else {
            // Fog scenarios: cloud_analytics is pinned to the cloud (it is a
            // dashboard/retraining sink, not part of the real-time path);
            // fastpath_filter, fog_processing, and (if present) ids_inference
            // are left unmapped so ModulePlacementEdgewards places them at the
            // fog node nearest each sensor, per the architecture's edge-ward design.
            moduleMapping.addModuleToDevice(AppGraph.MOD_CLOUD_ANALYTICS, "cloud");
            placement = new ModulePlacementEdgewards(topo.fogDevices, topo.sensors, topo.actuators, application, moduleMapping);
        }

        controller.submitApplication(application, placement);

        TimeKeeper.getInstance().setSimulationStartTime(Calendar.getInstance().getTimeInMillis());

        CloudSim.startSimulation();
        // MetricsController.processEvent handles STOP_SIMULATION, writes output,
        // and calls System.exit(0); execution does not fall through to here
        // under normal operation.
    }
}

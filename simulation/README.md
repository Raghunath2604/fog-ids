# fog-ids / simulation — Member B

Status: iFogSim2 vendored and building. Topology and scenarios not yet added
(see feature/ifogsim-topology and feature/ids-integration).

## Build

Maven Central isn't reachable in every environment, so two build paths work:

**Reference build (dev machine with internet access):**
```
cd simulation
mvn compile
```

**Direct build (no Maven plugin resolution required):**
```
cd simulation
find src/main/java -name "*.java" > /tmp/sources.txt
javac -d target/classes -cp "$(find lib -name '*.jar' | tr '\n' ':')" @/tmp/sources.txt
```

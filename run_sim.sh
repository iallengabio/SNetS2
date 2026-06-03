CP="/Users/iallen/.m2/repository/com/fasterxml/jackson/core/jackson-databind/2.18.2/jackson-databind-2.18.2.jar:/Users/iallen/.m2/repository/com/fasterxml/jackson/core/jackson-annotations/2.18.2/jackson-annotations-2.18.2.jar:/Users/iallen/.m2/repository/com/fasterxml/jackson/core/jackson-core/2.18.2/jackson-core-2.18.2.jar"
mkdir -p target/classes
javac -cp "$CP" -d target/classes src/main/java/com/snets2/**/*.java src/main/java/com/snets2/*.java
java -cp "target/classes:$CP" com.snets2.MainRunner experiments/experiment01 | grep "RandomFit called" | head -n 10

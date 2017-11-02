package com.streamlio.messaging.benchmark;

import java.io.File;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParameterException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.streamlio.messaging.benchmark.driver.BenchmarkDriver;

public class Benchmark {

    static class Arguments {

        @Parameter(names = { "-h", "--help" }, description = "Help message", help = true)
        boolean help;

        @Parameter(names = { "-d",
                "--drivers" }, description = "Drivers list. eg.: pulsar/pulsar.yaml,kafka/kafka.yaml", required = true)
        public List<String> drivers;

        @Parameter(description = "Workloads", required = true)
        public List<String> workloads;
    }

    public static void main(String[] args) throws Exception {
        final Arguments arguments = new Arguments();
        JCommander jc = new JCommander(arguments);
        jc.setProgramName("messaging-benchmark");

        try {
            jc.parse(args);
        } catch (ParameterException e) {
            System.err.println(e.getMessage());
            jc.usage();
            System.exit(-1);
        }

        if (arguments.help) {
            jc.usage();
            System.exit(-1);
        }

        // Dump configuration variables
        log.info("Starting benchmark with config: {}", writer.writeValueAsString(arguments));

        Map<String, Workload> workloads = new TreeMap<>();
        for (String path : arguments.workloads) {
            File file = new File(path);
            String name = file.getName().substring(0, file.getName().lastIndexOf('.'));

            workloads.put(name, mapper.readValue(file, Workload.class));
        }

        log.info("Workloads: {}", writer.writeValueAsString(workloads));

        Map<String, BenchmarkDriver> drivers = new TreeMap<>();

        for (String driverConfig : arguments.drivers) {
            File driverConfigFile = new File(driverConfig);
            DriverConfiguration driverConfiguration = mapper.readValue(driverConfigFile, DriverConfiguration.class);

            log.info("Driver: {}", writer.writeValueAsString(driverConfiguration));
            BenchmarkDriver driver = (BenchmarkDriver) Class.forName(driverConfiguration.driverClass).newInstance();
            driver.initialize(driverConfigFile);
            drivers.put(driverConfiguration.name, driver);
        }

        workloads.forEach((workloadName, workload) -> {
            drivers.forEach((driverName, driver) -> {
                log.info("--------------- WORKLOAD : {} --- DRIVER : {}---------------", workload.name, driverName);

                WorkloadGenerator generator = new WorkloadGenerator(driverName, driver, workload);
                try {
                    TestResult result = generator.run();

                    String fileName = String.format("%s-%s-%s.json", workloadName, driverName,
                            dateFormat.format(new Date()));

                    log.info("Writing test result into {}", fileName);
                    writer.writeValue(new File(fileName), result);

                    generator.close();
                } catch (Exception e) {
                    log.error("Failed to run the workload '{}' for driver '{}'", workload.name, driverName, e);
                }
            });
        });

        for (BenchmarkDriver driver : drivers.values()) {
            driver.close();
        }
    }

    private static final ObjectMapper mapper = new ObjectMapper(new YAMLFactory())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private static final ObjectWriter writer = new ObjectMapper().writerWithDefaultPrettyPrinter();

    private static final DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd-HH-mm-ss");

    private static final Logger log = LoggerFactory.getLogger(Benchmark.class);
}

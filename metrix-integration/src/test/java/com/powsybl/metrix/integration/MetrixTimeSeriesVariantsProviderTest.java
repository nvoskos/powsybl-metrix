package com.powsybl.metrix.integration;

import com.google.common.collect.Range;
import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import com.powsybl.contingency.BranchContingency;
import com.powsybl.contingency.ContingenciesProvider;
import com.powsybl.contingency.Contingency;
import com.powsybl.iidm.network.*;
import com.powsybl.iidm.xml.NetworkXml;
import com.powsybl.metrix.integration.contingency.Probability;
import com.powsybl.metrix.mapping.MappingParameters;
import com.powsybl.metrix.mapping.TimeSeriesDslLoader;
import com.powsybl.metrix.mapping.TimeSeriesMappingConfig;
import com.powsybl.timeseries.*;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.threeten.extra.Interval;

import java.io.*;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.*;

import static org.junit.Assert.assertEquals;

public class MetrixTimeSeriesVariantsProviderTest {
    private FileSystem fileSystem;
    private Path metrixFile;
    private Path variantFile;
    private Network network;

    private static final char SEPARATOR = ';';

    private final MappingParameters mappingParameters = MappingParameters.load();

    @Before
    public void setUp() {
        fileSystem = Jimfs.newFileSystem(Configuration.unix());
        metrixFile = fileSystem.getPath("/metrix.groovy");
        variantFile = fileSystem.getPath("/variantes.csv");
        network = NetworkXml.read(getClass().getResourceAsStream("/simpleNetwork.xml"));
        network.getLoad("FVERGE11_L").getTerminal().connect(); // Connect 4th load to use it
    }

    @After
    public void tearDown() throws IOException {
        fileSystem.close();
    }

    @Test
    public void variantsTest() throws IOException, URISyntaxException {
        Path workingDir = Paths.get(getClass().getResource("/").toURI());
        // Creates metrix file
        try (Writer writer = Files.newBufferedWriter(metrixFile, StandardCharsets.UTF_8)) {
            writer.write(String.join(System.lineSeparator(),
                    "branch('FVALDI1  FTDPRA1  1') {",
                    "    branchRatingsBaseCase 999",
                    "}",
                    "branch('FS.BIS1  FVALDI1  1') {",
                    "    branchRatingsBaseCase 'constant_ts3'",
                    "}",
                    "branch('FP.AND1  FVERGE1  2') {",
                    "    branchRatingsBaseCase 'variable_ts3'",
                    "}"
            ));
        }

        // create time series space mock
        TimeSeriesIndex index = RegularTimeSeriesIndex.create(Interval.parse("2015-01-01T00:00:00Z/2015-07-20T00:00:00Z"), Duration.ofDays(200));
        ReadOnlyTimeSeriesStore store = new ReadOnlyTimeSeriesStoreCache(
                TimeSeries.createDouble("constant_ts1", index, 100d, 100d),
                TimeSeries.createDouble("variable_ts1", index, 200d, 201d),
                TimeSeries.createDouble("constant_ts2", index, 300d, 300d),
                TimeSeries.createDouble("variable_ts2", index, 400d, 401d),
                TimeSeries.createDouble("variable_ts3", index, 600d, 601d)
        );

        ContingenciesProvider contingenciesProvider = network -> {
            Contingency a = new Contingency("a", Collections.singletonList(new BranchContingency("FVALDI1  FTDPRA1  1")));
            a.addExtension(Probability.class, new Probability(0.002d, null));
            Contingency b = new Contingency("b", Arrays.asList(new BranchContingency("FS.BIS1  FVALDI1  1"), new BranchContingency("FP.AND1  FVERGE1  2")));
            b.addExtension(Probability.class, new Probability(null, "variable_ts1"));
            return Arrays.asList(a, b);
        };

        MetrixParameters metrixParameters = MetrixParameters.load();
        MetrixNetwork metrixNetwork = MetrixNetwork.create(network, contingenciesProvider, null, new MetrixParameters(), (Path) null);

        TimeSeriesMappingConfig mappingConfig;
        try (Reader mappingReader = new InputStreamReader(MetrixConstantVariantTest.class.getResourceAsStream("/inputs/constantVariantTestMappingInput.groovy"), StandardCharsets.UTF_8)) {
            mappingConfig = TimeSeriesDslLoader.load(mappingReader, network, mappingParameters, store, null, null);
        }

        try (Reader metrixDslReader = Files.newBufferedReader(metrixFile, StandardCharsets.UTF_8)) {
            MetrixDslDataLoader.load(metrixDslReader, network, metrixParameters, store, mappingConfig);
        }

        Range<Integer> variantRange = Range.closed(0, 1);

        MetrixDslData metrixDslData = new MetrixDslData();
        metrixDslData.addHvdcFlowResults("HVDC1");
        metrixDslData.addHvdcFlowResults("HVDC2");
        metrixDslData.addPstAngleTapResults("FP.AND1  FTDPRA1  1");

        MetrixVariantProvider variantProvider = new MetrixTimeSeriesVariantProvider(network, store, mappingParameters,
                mappingConfig, metrixDslData, network -> Collections.emptyList(), 1, variantRange, false, false, true, System.err);

        try (BufferedWriter writer = Files.newBufferedWriter(variantFile, StandardCharsets.UTF_8)) {
            variantProvider.readVariants(Range.closed(0, 1), new MetrixVariantReaderImpl(metrixNetwork, writer, SEPARATOR), workingDir);
        }

        assertEquals(String.join(System.lineSeparator(),
                "-1;CONELE;1;FSSV.O11_L;300;",
                "-1;QATI00MN;2;FVALDI1  FTDPRA1  1;999;FS.BIS1  FVALDI1  1;500;",
                "0;PRODIM;2;FSSV.O11_G;100;FSSV.O12_G;200;",
                "0;CONELE;1;FVALDI11_L;400;",
                "0;QATI00MN;1;FP.AND1  FVERGE1  2;600;",
                "0;PROBABINC;2;b;200;a;0.002;",
                "1;PRODIM;2;FSSV.O11_G;100;FSSV.O12_G;201;",
                "1;CONELE;1;FVALDI11_L;401;",
                "1;QATI00MN;1;FP.AND1  FVERGE1  2;601;",
                "1;PROBABINC;2;b;201;a;0.002;") + System.lineSeparator(),
                new String(Files.readAllBytes(variantFile), StandardCharsets.UTF_8));
    }
}
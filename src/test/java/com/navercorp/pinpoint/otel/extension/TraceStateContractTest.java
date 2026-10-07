/*
 * Copyright 2026 NAVER Corp.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.navercorp.pinpoint.otel.extension;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the wire contract in {@code src/test/resources/pp-tracestate-contract.txt}.
 *
 * <p>The same file lives in the Pinpoint OTLP trace collector, where its parser asserts the reverse
 * direction. A change to either side must update the file in both places and bump the
 * {@code contract-version} line.
 */
class TraceStateContractTest {

    private static final String CONTRACT_FILE = "/pp-tracestate-contract.txt";
    private static final int CONTRACT_VERSION = 1;

    @Test
    void entryKeyIsPp() {
        assertThat(PinpointTraceStateSpec.KEY).isEqualTo("pp");
    }

    @Test
    void contractVersionIsPinned() throws IOException {
        List<String> lines = readLines();
        assertThat(lines).isNotEmpty();
        assertThat(lines.get(0)).isEqualTo("# contract-version: " + CONTRACT_VERSION);
    }

    @Test
    void producesEveryContractValue() throws IOException {
        int cases = 0;
        for (String line : readLines()) {
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] cols = line.split("\\|", -1);
            assertThat(cols).as("columns in '%s'", line).hasSize(4);
            String svc = column(cols[0]);
            String app = column(cols[1]);
            Integer type = cols[2].equals("-") ? null : Integer.valueOf(cols[2]);
            String expected = cols[3].equals("<null>") ? null : cols[3];

            String actual = PinpointTraceStateSpec.buildValue(svc, app, type);
            assertThat(actual).as("pp value for svc=%s app=%s type=%s", svc, app, type).isEqualTo(expected);
            cases++;
        }
        assertThat(cases).isGreaterThanOrEqualTo(10);
    }

    private static String column(String raw) {
        return raw.equals("-") ? null : raw;
    }

    private static List<String> readLines() throws IOException {
        InputStream in = TraceStateContractTest.class.getResourceAsStream(CONTRACT_FILE);
        assertThat(in).as("contract file %s", CONTRACT_FILE).isNotNull();
        List<String> lines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
            }
        }
        return lines;
    }
}

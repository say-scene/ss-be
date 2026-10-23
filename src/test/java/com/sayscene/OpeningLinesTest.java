package com.sayscene;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class OpeningLinesTest {
    @Test void eachScenarioHasTenDistinctOpeningsAndSessionSelectionIsStable() {
        for (String scenario : List.of("cafe-order", "restaurant-order", "clothes-shopping", "subway-route", "street-directions", "hotel-checkin", "market-shopping", "pharmacy-visit", "phone-reservation", "infra-interview")) {
            var selected = new HashSet<String>();
            for (int i = 0; i < 1000; i++) {
                String session = new UUID(0, i).toString();
                String line = OpeningLines.pick(scenario, session);
                assertThat(line).isNotBlank().isEqualTo(OpeningLines.pick(scenario, session));
                selected.add(line);
            }
            assertThat(selected).hasSize(10);
        }
    }
}

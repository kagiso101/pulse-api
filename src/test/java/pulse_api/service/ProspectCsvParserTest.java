package pulse_api.service;

import org.junit.jupiter.api.Test;
import pulse_api.entity.Enums;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class ProspectCsvParserTest {

    private final ProspectCsvParser parser = new ProspectCsvParser();

    @Test
    void mapsHeadersCaseInsensitivelyWithAliases() {
        String csv = "Name,Business,PHONE,Area,Website,Status,Next Action,Next Action Date,Notes\r\n"
                + "Thandi,Blaauwberg Nails,0821234567,Table View,yes,contacted,Call back,2026-09-20,Prefers WhatsApp\r\n";
        var parsed = parser.parse(csv);

        assertThat(parsed.errors()).isEmpty();
        assertThat(parsed.rows()).hasSize(1);
        var row = parsed.rows().get(0);
        assertThat(row.name()).isEqualTo("Thandi");
        assertThat(row.business()).isEqualTo("Blaauwberg Nails");
        assertThat(row.phone()).isEqualTo("0821234567");
        assertThat(row.area()).isEqualTo("Table View");
        assertThat(row.hasWebsite()).isTrue();
        assertThat(row.status()).isEqualTo(Enums.ProspectStatus.contacted);
        assertThat(row.nextAction()).isEqualTo("Call back");
        assertThat(row.nextActionDate()).isEqualTo(LocalDate.of(2026, 9, 20));
        assertThat(row.notes()).isEqualTo("Prefers WhatsApp");
    }

    @Test
    void handlesQuotedCommasEscapedQuotesAndNewlinesInsideQuotes() {
        String csv = "name,business,notes\n"
                + "\"Sipho, Jr\",\"The \"\"Barber\"\" Shop\",\"line one\nline two\"\n";
        var parsed = parser.parse(csv);

        assertThat(parsed.errors()).isEmpty();
        assertThat(parsed.rows()).hasSize(1);
        assertThat(parsed.rows().get(0).name()).isEqualTo("Sipho, Jr");
        assertThat(parsed.rows().get(0).business()).isEqualTo("The \"Barber\" Shop");
        assertThat(parsed.rows().get(0).notes()).isEqualTo("line one\nline two");
    }

    @Test
    void countsBadRowsAndKeepsGoodOnes() {
        String csv = "name,phone,status,next_action_date\n"
                + "Good One,0820000001,to_contact,2026-10-01\n"
                + ",0820000002,to_contact,\n"                 // missing name
                + "Bad Status,0820000003,maybe,\n"           // unknown status
                + "Bad Date,0820000004,contacted,20/10/2026\n" // not ISO
                + "\n"                                        // blank line ignored
                + "Defaults Only,,,\n";
        var parsed = parser.parse(csv);

        assertThat(parsed.rows()).extracting("name").containsExactly("Good One", "Defaults Only");
        assertThat(parsed.rows().get(1).status()).isEqualTo(Enums.ProspectStatus.to_contact);
        assertThat(parsed.rows().get(1).phone()).isNull();
        assertThat(parsed.errors()).hasSize(3);
        assertThat(parsed.errors()).anySatisfy(e -> assertThat(e).contains("Row 3").contains("name"));
        assertThat(parsed.errors()).anySatisfy(e -> assertThat(e).contains("Row 4").contains("status"));
        assertThat(parsed.errors()).anySatisfy(e -> assertThat(e).contains("Row 5").contains("YYYY-MM-DD"));
    }

    @Test
    void rejectsCsvWithoutNameColumnOrEmptyInput() {
        assertThat(parser.parse("business,phone\nX,1\n").errors()).containsExactly("CSV needs a 'name' column");
        assertThat(parser.parse("").errors()).containsExactly("CSV is empty");
        assertThat(parser.parse("﻿name\nBom Person\n").rows()).extracting("name").containsExactly("Bom Person");
    }
}

package dev.subnetory.web;

import static org.assertj.core.api.Assertions.assertThat;

import dev.subnetory.dto.SeriesTemplateDefinition;
import dev.subnetory.dto.SeriesTemplateRequest;
import dev.subnetory.dto.SeriesTemplateResponse;
import dev.subnetory.web.form.SeriesTemplateForm;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Conversion formulaire <-> template ({@link SeriesTemplateForm}). */
class SeriesTemplateFormTest {

    @Test
    void from_copiesDefinitionAndPadsGroupLines() {
        SeriesTemplateDefinition definition = new SeriesTemplateDefinition("interleaved", 2, 3, "skip", List.of(
                new SeriesTemplateDefinition.Group("HOST", "Hotes", "{cluster}-{role}-{nn}", 4, 7L),
                new SeriesTemplateDefinition.Group("CVM", null, "{cluster}-{role}-{nn}", null, null)));
        SeriesTemplateResponse response = new SeriesTemplateResponse("custom:7", false, 7L, 1L,
                "Mon cluster", "Desc", definition, null);

        SeriesTemplateForm form = SeriesTemplateForm.from(response);

        assertThat(form.getId()).isEqualTo(7L);
        assertThat(form.getContextId()).isEqualTo(1L);
        assertThat(form.getName()).isEqualTo("Mon cluster");
        assertThat(form.getDescription()).isEqualTo("Desc");
        assertThat(form.getAllocation()).isEqualTo("interleaved");
        assertThat(form.getGroupGap()).isEqualTo(2);
        assertThat(form.getIpStep()).isEqualTo(3);
        assertThat(form.getOnOccupied()).isEqualTo("skip");
        assertThat(form.getGroups()).hasSize(SeriesTemplateForm.GROUP_LINES);
        assertThat(form.getGroups().get(0).getLabel()).isEqualTo("Hotes");
        assertThat(form.getGroups().get(0).getCount()).isEqualTo(4);
        assertThat(form.getGroups().get(1).getLabel()).isEmpty();
        assertThat(form.getGroups().get(2).isBlank()).isTrue();
    }

    @Test
    void toRequest_ignoresBlankLinesAndTrimsValues() {
        SeriesTemplateForm form = new SeriesTemplateForm();
        form.setContextId(3L);
        form.setName("Mon cluster");
        form.getGroups().get(0).setRole(" HOST ");
        form.getGroups().get(0).setLabel("  ");
        form.getGroups().get(0).setFormat(" {cluster}-{nn} ");
        form.getGroups().get(3).setRole("IPMI");
        form.getGroups().get(3).setFormat("{cluster}-{role}-{nn}");
        form.getGroups().get(3).setCount(2);

        SeriesTemplateRequest request = form.toRequest();

        assertThat(request.contextId()).isEqualTo(3L);
        assertThat(request.definition().groups()).hasSize(2);
        SeriesTemplateDefinition.Group first = request.definition().groups().get(0);
        assertThat(first.role()).isEqualTo("HOST");
        assertThat(first.label()).isNull();
        assertThat(first.format()).isEqualTo("{cluster}-{nn}");
        assertThat(request.definition().groups().get(1).count()).isEqualTo(2);
        assertThat(request.definition().allocation()).isEqualTo(SeriesTemplateDefinition.SEQUENTIAL);
    }
}

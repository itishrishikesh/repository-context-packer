package dev.hrishikesh.rer.context.output;

import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import dev.hrishikesh.rer.context.planning.PackagePlanner.Plan;
import java.io.IOException;

public final class JsonManifestRenderer {
  private final ObjectMapper mapper =
      JsonMapper.builder().enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY).build();

  public String render(Plan plan) throws IOException {
    var printer = new DefaultPrettyPrinter();
    printer.indentObjectsWith(new DefaultIndenter("  ", "\n"));
    printer.indentArraysWith(new DefaultIndenter("  ", "\n"));
    return mapper.writer(printer).writeValueAsString(plan) + "\n";
  }
}

package com.milano.quotation.logistics;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import tools.jackson.databind.ObjectMapper;
import java.nio.file.*;
class SfCorpusSnapshotTest {
 @Test @EnabledIfSystemProperty(named="sf.corpusDir",matches=".+")
 void snapshotRealWorkbooksForCrossVersionComparison() throws Exception {
  var mapper=new ObjectMapper();var parser=new LogisticsSourceParser(mapper,new LogisticsWorkbookService(mapper));var output=mapper.createObjectNode();
  try(var paths=Files.list(Path.of(System.getProperty("sf.corpusDir")))) {
   for(var file:paths.filter(p->!p.getFileName().toString().startsWith("~$")&&p.toString().matches("(?i).*\\.xlsx?$")).sorted().toList())output.set(file.getFileName().toString(),parser.parse(Files.readAllBytes(file),file.getFileName().toString()));
  }
  Files.createDirectories(Path.of("target/sf-discount"));Files.writeString(Path.of("target/sf-discount/corpus-snapshot.json"),mapper.writeValueAsString(output));
 }
}

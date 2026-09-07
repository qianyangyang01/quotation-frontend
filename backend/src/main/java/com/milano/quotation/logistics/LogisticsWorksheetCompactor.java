package com.milano.quotation.logistics;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.events.XMLEvent;

/** Remove formatting-only cells before POI allocates its workbook object model. */
final class LogisticsWorksheetCompactor {
    private LogisticsWorksheetCompactor() {}
    static void copy(InputStream input, OutputStream output) throws Exception {
        var factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty("javax.xml.stream.isSupportingExternalEntities", false);
        var reader = factory.createXMLEventReader(new java.io.FilterInputStream(input) { @Override public void close() {} });
        var writer = XMLOutputFactory.newFactory().createXMLEventWriter(output, "UTF-8");
        var row = new ArrayList<XMLEvent>();
        boolean inRow = false; boolean hasValue = false;
        while (reader.hasNext()) {
            var event = reader.nextEvent();
            var start = event.isStartElement() ? event.asStartElement().getName().getLocalPart() : "";
            var end = event.isEndElement() ? event.asEndElement().getName().getLocalPart() : "";
            if (start.equals("row")) { inRow = true; hasValue = false; row.clear(); }
            if (inRow) {
                row.add(event);
                if (start.equals("v") || start.equals("f") || start.equals("is")) hasValue = true;
                if (end.equals("row")) {
                    if (hasValue) for (var item : row) writer.add(item);
                    else { writer.add(row.getFirst()); writer.add(event); }
                    row.clear(); inRow = false;
                }
            } else writer.add(event);
        }
        writer.flush();
        // The enclosing ZIP stream owns the input/output lifecycle.
    }
}

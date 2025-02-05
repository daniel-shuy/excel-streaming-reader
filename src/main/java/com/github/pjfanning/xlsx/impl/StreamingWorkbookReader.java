package com.github.pjfanning.xlsx.impl;

import com.github.pjfanning.poi.xssf.streaming.MapBackedSharedStringsTable;
import com.github.pjfanning.poi.xssf.streaming.TempFileSharedStringsTable;
import com.github.pjfanning.xlsx.SharedStringsImplementationType;
import com.github.pjfanning.xlsx.StreamingReader.Builder;
import com.github.pjfanning.xlsx.exceptions.NotSupportedException;
import com.github.pjfanning.xlsx.exceptions.OpenException;
import com.github.pjfanning.xlsx.exceptions.ParseException;
import com.github.pjfanning.xlsx.exceptions.ReadException;
import com.github.pjfanning.xlsx.impl.ooxml.OoxmlStrictHelper;
import com.github.pjfanning.xlsx.impl.ooxml.OoxmlReader;
import org.apache.commons.io.IOUtils;
import org.apache.poi.ooxml.POIXMLProperties;
import org.apache.poi.openxml4j.exceptions.InvalidFormatException;
import org.apache.poi.openxml4j.exceptions.OpenXML4JException;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackagePart;
import org.apache.poi.poifs.crypt.Decryptor;
import org.apache.poi.poifs.crypt.EncryptionInfo;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.apache.poi.ss.usermodel.Date1904Support;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.util.XMLHelper;
import org.apache.poi.xssf.model.*;
import org.apache.poi.xssf.usermodel.XSSFShape;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.util.*;

import static com.github.pjfanning.xlsx.XmlUtils.readDocument;
import static com.github.pjfanning.xlsx.XmlUtils.searchForNodeList;

public class StreamingWorkbookReader implements Iterable<Sheet>, Date1904Support, AutoCloseable {
  private static final Logger log = LoggerFactory.getLogger(StreamingWorkbookReader.class);
  private static XMLInputFactory xmlInputFactory;

  private final List<StreamingSheet> sheets;
  private final List<Map<String, String>> sheetProperties = new ArrayList<>();
  private final Map<String, List<XSSFShape>> shapeMap = new HashMap<>();
  private final Builder builder;
  private File tmp;
  private OPCPackage pkg;
  private SharedStrings sst;
  private boolean use1904Dates = false;
  private StreamingWorkbook workbook = null;
  private POIXMLProperties.CoreProperties coreProperties = null;

  public StreamingWorkbookReader(Builder builder) {
    this.sheets = new ArrayList<>();
    this.builder = builder;
  }

  public void init(InputStream is) {
    if (builder.avoidTempFiles()) {
      try {
        if(builder.getPassword() != null) {
          POIFSFileSystem poifs = new POIFSFileSystem(is);
          pkg = decryptWorkbook(poifs);
        } else {
          pkg = OPCPackage.open(is);
        }
        loadPackage(pkg);
      } catch(SAXException e) {
        IOUtils.closeQuietly(pkg);
        throw new ParseException("Failed to parse stream", e);
      } catch(IOException e) {
        IOUtils.closeQuietly(pkg);
        throw new OpenException("Failed to open stream", e);
      } catch(OpenXML4JException | XMLStreamException e) {
        IOUtils.closeQuietly(pkg);
        throw new ReadException("Unable to read workbook", e);
      } catch(GeneralSecurityException e) {
        IOUtils.closeQuietly(pkg);
        throw new ReadException("Unable to read workbook - Decryption failed", e);
      }
    } else {
      File f = null;
      try {
        f = TempFileUtil.writeInputStreamToFile(is, builder.getBufferSize());
        if (log.isDebugEnabled()) {
          log.debug("Created temp file [{}]", f.getAbsolutePath());
        }
        init(f);
        tmp = f;
      } catch(IOException e) {
        if(f != null && !f.delete()) {
          log.debug("failed to delete temp file");
        }
        throw new ReadException("Unable to read input stream", e);
      } catch(RuntimeException e) {
        if(f != null && !f.delete()) {
          log.debug("failed to delete temp file");
        }
        throw e;
      }
    }
  }

  public void init(File f) {
    try {
      if(builder.getPassword() != null) {
        POIFSFileSystem poifs = new POIFSFileSystem(f);
        pkg = decryptWorkbook(poifs);
      } else {
        pkg = OPCPackage.open(f);
      }
      loadPackage(pkg);
    } catch(SAXException e) {
      IOUtils.closeQuietly(pkg);
      throw new ParseException("Failed to parse file", e);
    } catch(IOException e) {
      IOUtils.closeQuietly(pkg);
      throw new OpenException("Failed to open file", e);
    } catch(OpenXML4JException | XMLStreamException e) {
      IOUtils.closeQuietly(pkg);
      throw new ReadException("Unable to read workbook", e);
    } catch(GeneralSecurityException e) {
      IOUtils.closeQuietly(pkg);
      throw new ReadException("Unable to read workbook - Decryption failed", e);
    }
  }

  private OPCPackage decryptWorkbook(POIFSFileSystem poifs) throws IOException, GeneralSecurityException, InvalidFormatException {
    // Based on: https://poi.apache.org/encryption.html
    EncryptionInfo info = new EncryptionInfo(poifs);
    Decryptor d = Decryptor.getInstance(info);
    d.verifyPassword(builder.getPassword());
    return OPCPackage.open(d.getDataStream(poifs));
  }

  private void loadPackage(OPCPackage pkg) throws IOException, OpenXML4JException, SAXException, XMLStreamException {
    boolean strictFormat = pkg.isStrictOoxmlFormat();
    OoxmlReader reader = new OoxmlReader(this, pkg, strictFormat);
    if (strictFormat) {
      log.info("file is in strict OOXML format");
    }
    if (builder.getSharedStringsImplementationType() == SharedStringsImplementationType.TEMP_FILE_BACKED) {
      log.info("Created sst cache file");
      sst = new TempFileSharedStringsTable(pkg, builder.encryptSstTempFile(), builder.fullFormatRichText());
    } else if (builder.getSharedStringsImplementationType() == SharedStringsImplementationType.CUSTOM_MAP_BACKED) {
      sst = new MapBackedSharedStringsTable(pkg, builder.fullFormatRichText());
    } else if (strictFormat) {
      sst = OoxmlStrictHelper.getSharedStringsTable(builder, pkg);
    } else {
      sst = reader.getSharedStrings(builder);
    }

    if (builder.readCoreProperties()) {
      try {
        POIXMLProperties xmlProperties = new POIXMLProperties(pkg);
        coreProperties = xmlProperties.getCoreProperties();
      } catch (Exception e) {
        log.warn("Failed to read coreProperties", e);
      }
    }

    StylesTable styles = null;
    if (builder.readStyles()) {
      if (strictFormat) {
        ThemesTable themesTable = OoxmlStrictHelper.getThemesTable(builder, pkg);
        styles = OoxmlStrictHelper.getStylesTable(builder, pkg);
        styles.setTheme(themesTable);
      } else {
        styles = reader.getStylesTable();
      }
    }

    Document workbookDoc = readDocument(reader.getWorkbookData());
    use1904Dates = WorkbookUtil.use1904Dates(workbookDoc);

    loadSheets(workbookDoc, reader, sst, styles);
  }

  void setWorkbook(StreamingWorkbook workbook) {
    this.workbook = workbook;
    workbook.setCoreProperties(coreProperties);
  }

  Workbook getWorkbook() {
    return workbook;
  }

  private void loadSheets(Document workbookDoc, OoxmlReader reader, SharedStrings sst, StylesTable stylesTable)
          throws IOException, XMLStreamException {
    lookupSheetNames(workbookDoc);

    //Some workbooks have multiple references to the same sheet. Need to filter
    //them out before creating the XMLEventReader by keeping track of their URIs.
    //The sheets are listed in order, so we must keep track of insertion order.
    OoxmlReader.OoxmlSheetIterator iter = reader.getSheetsData();
    Map<PackagePart, InputStream> sheetStreams = new LinkedHashMap<>();
    Map<PackagePart, Comments> sheetComments = new HashMap<>();
    while(iter.hasNext()) {
      InputStream is = iter.next();
      if (builder.readShapes()) {
        shapeMap.put(iter.getSheetName(), iter.getShapes());
      }
      PackagePart part = iter.getSheetPart();
      sheetStreams.put(part, is);
      if (builder.readComments()) {
        sheetComments.put(part, iter.getSheetComments(builder));
      }
    }

    //Iterate over the loaded streams
    int i = 0;
    for(Map.Entry<PackagePart, InputStream> entry : sheetStreams.entrySet()) {
      XMLEventReader parser = getXmlInputFactory().createXMLEventReader(entry.getValue());
      sheets.add(new StreamingSheet(
              sheetProperties.get(i++).get("name"),
              new StreamingSheetReader(this, entry.getKey(), sst, stylesTable,
                      sheetComments.get(entry.getKey()), parser, use1904Dates, builder.getRowCacheSize())));
    }
  }

  private void lookupSheetNames(Document workbookDoc) {
    sheetProperties.clear();
    NodeList nl = searchForNodeList(workbookDoc, "/ss:workbook/ss:sheets/ss:sheet");
    for(int i = 0; i < nl.getLength(); i++) {
      Map<String, String> props = new HashMap<>();
      props.put("name", nl.item(i).getAttributes().getNamedItem("name").getTextContent());

      Node state = nl.item(i).getAttributes().getNamedItem("state");
      props.put("state", state == null ? "visible" : state.getTextContent());
      sheetProperties.add(props);
    }
  }

  List<StreamingSheet> getSheets() {
    return sheets;
  }

  public List<Map<String, String>> getSheetProperties() {
    return sheetProperties;
  }

  @Override
  public Iterator<Sheet> iterator() {
    return new StreamingSheetIterator(sheets.iterator());
  }

  @Override
  public Spliterator<Sheet> spliterator() {
    return Spliterators.spliterator(sheets, Spliterator.ORDERED);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean isDate1904() {
    return use1904Dates;
  }

  @Override
  public void close() throws IOException {
    try {
      for(StreamingSheet sheet : sheets) {
        sheet.getReader().close();
      }
      pkg.revert();
    } finally {
      if(tmp != null) {
        if (log.isDebugEnabled()) {
          log.debug("Deleting tmp file [{}]", tmp.getAbsolutePath());
        }
        if (!tmp.delete()) {
          log.debug("Failed tp delete temp file");
        }
      }
      if(sst instanceof Closeable) {
        ((Closeable)sst).close();
      }
    }
  }

  Builder getBuilder() {
    return builder;
  }

  OPCPackage getOPCPackage() {
    return pkg;
  }

  List<XSSFShape> getShapes(String sheetName) {
    return shapeMap.get(sheetName);
  }

  private static XMLInputFactory getXmlInputFactory() {
    if (xmlInputFactory == null) {
      try {
        xmlInputFactory = XMLHelper.newXMLInputFactory();
      } catch (Exception e) {
        log.error("Issue creating XMLInputFactory", e);
        throw e;
      }
    }
    return xmlInputFactory;
  }

  static class StreamingSheetIterator implements Iterator<Sheet> {
    private final Iterator<StreamingSheet> iterator;

    public StreamingSheetIterator(Iterator<StreamingSheet> iterator) {
      this.iterator = iterator;
    }

    @Override
    public boolean hasNext() {
      return iterator.hasNext();
    }

    @Override
    public Sheet next() {
      return iterator.next();
    }

    @Override
    public void remove() {
      throw new NotSupportedException();
    }
  }
}

/*!
 * This program is free software; you can redistribute it and/or modify it under the
 * terms of the GNU Lesser General Public License, version 2.1 as published by the Free Software
 * Foundation.
 *
 * You should have received a copy of the GNU Lesser General Public License along with this
 * program; if not, you can obtain a copy at http://www.gnu.org/licenses/old-licenses/lgpl-2.1.html
 * or from the Free Software Foundation, Inc.,
 * 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU Lesser General Public License for more details.
 *
 * Copyright (c) 2002-2019 Hitachi Vantara.  All rights reserved.
 */

package org.pentaho.reporting.platform.plugin;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.pentaho.platform.api.action.IStreamProcessingAction;
import org.pentaho.platform.api.action.IStreamingAction;
import org.pentaho.platform.api.action.IVarArgsAction;
import org.pentaho.platform.api.engine.IActionSequenceResource;
import org.pentaho.platform.api.engine.IPluginManager;
import org.pentaho.platform.api.repository2.unified.RepositoryFile;
import org.pentaho.platform.engine.core.system.PentahoSystem;
import org.pentaho.platform.repository2.unified.fileio.RepositoryFileInputStream;
import org.pentaho.reporting.engine.classic.core.AttributeNames;
import org.pentaho.reporting.engine.classic.core.MasterReport;
import org.pentaho.reporting.engine.classic.core.metadata.ReportProcessTaskRegistry;
import org.pentaho.reporting.engine.classic.core.modules.output.pageable.pdf.PdfPageableModule;
import org.pentaho.reporting.engine.classic.core.modules.output.pageable.plaintext.PlainTextPageableModule;
import org.pentaho.reporting.engine.classic.core.modules.output.pageable.xml.XmlPageableModule;
import org.pentaho.reporting.engine.classic.core.modules.output.table.csv.CSVTableModule;
import org.pentaho.reporting.engine.classic.core.modules.output.table.html.HtmlTableModule;
import org.pentaho.reporting.engine.classic.core.modules.output.table.rtf.RTFTableModule;
import org.pentaho.reporting.engine.classic.core.modules.output.table.xls.ExcelTableModule;
import org.pentaho.reporting.engine.classic.core.modules.output.table.xml.XmlTableModule;
import org.pentaho.reporting.engine.classic.core.parameters.DefaultParameterContext;
import org.pentaho.reporting.engine.classic.core.parameters.ParameterContext;
import org.pentaho.reporting.engine.classic.core.parameters.ParameterDefinitionEntry;
import org.pentaho.reporting.engine.classic.core.parameters.ValidationMessage;
import org.pentaho.reporting.engine.classic.core.parameters.ValidationResult;
import org.pentaho.reporting.engine.classic.core.util.ReportParameterValues;
import org.pentaho.reporting.engine.classic.extensions.modules.java14print.Java14PrintUtil;
import org.pentaho.reporting.libraries.base.config.Configuration;
import org.pentaho.reporting.libraries.base.config.ModifiableConfiguration;
import org.pentaho.reporting.libraries.base.util.CSVQuoter;
import org.pentaho.reporting.libraries.base.util.StringUtils;
import org.pentaho.reporting.libraries.resourceloader.ResourceException;
import org.pentaho.reporting.platform.plugin.async.ReportListenerThreadHolder;
import org.pentaho.reporting.platform.plugin.cache.DefaultReportCache;
import org.pentaho.reporting.platform.plugin.cache.NullReportCache;
import org.pentaho.reporting.platform.plugin.cache.ReportCache;
import org.pentaho.reporting.platform.plugin.cache.ReportCacheKey;
import org.pentaho.reporting.platform.plugin.messages.Messages;
import org.pentaho.reporting.platform.plugin.output.FastExportReportOutputHandlerFactory;
import org.pentaho.reporting.platform.plugin.output.ReportOutputHandler;
import org.pentaho.reporting.platform.plugin.output.ReportOutputHandlerFactory;
import org.pentaho.reporting.platform.plugin.output.ReportOutputHandlerSelector;

import javax.print.DocFlavor;
import javax.print.PrintService;
import javax.print.PrintServiceLookup;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Serializable;
import java.lang.reflect.Array;
import java.net.URL;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;

public class SimpleReportingAction implements IStreamProcessingAction, IStreamingAction, IVarArgsAction {

  /**
   * The logging for logging messages from this component
   */
  private static final Log log = LogFactory.getLog( SimpleReportingAction.class );

  public static final String OUTPUT_TARGET = "output-target";

  public static final String OUTPUT_TYPE = "output-type";
  public static final String MIME_TYPE_HTML = "text/html";
  public static final String MIME_TYPE_EMAIL = "mime-message/text/html";
  public static final String MIME_TYPE_PDF = "application/pdf";
  public static final String MIME_TYPE_XLS = "application/vnd.ms-excel";
  public static final String MIME_TYPE_XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
  public static final String MIME_TYPE_RTF = "application/rtf";
  public static final String MIME_TYPE_CSV = "text/csv";
  public static final String MIME_TYPE_TXT = "text/plain";
  public static final String MIME_TYPE_XML = "application/xml";
  public static final String MIME_TYPE_PNG = "image/png";

  public static final String XLS_WORKBOOK_PARAM = "workbook";

  public static final String REPORTLOAD_RESURL = "res-url";
  public static final String REPORT_DEFINITION_INPUT = "report-definition";
  public static final String USE_JCR = "useJcr";
  public static final String REPORTHTML_CONTENTHANDLER_PATTERN = "content-handler-pattern";
  public static final String REPORTGENERATE_YIELDRATE = "yield-rate";
  public static final String ACCEPTED_PAGE = "accepted-page";
  public static final String PAGINATE_OUTPUT = "paginate";
  public static final String PRINT = "print";
  public static final String PRINTER_NAME = "printer-name";
  public static final String DASHBOARD_MODE = "dashboard-mode";
  public static final String MIME_GENERIC_FALLBACK = "application/octet-stream";
  public static final String PNG_EXPORT_TYPE = "pageable/X-AWT-Graphics;image-type=png";

  public static final String RESERVEDMAPKEY_LINEAGE_ID = "lineage-id";

  /**
   * Static initializer block to guarantee that the ReportingComponent will be in a state where the reporting engine
   * will be booted. We have a system listener which will boot the reporting engine as well, but we do not want to
   * solely rely on users having this setup correctly. The errors you receive if the engine is not booted are not very
   * helpful, especially to outsiders, so we are trying to provide multiple paths to success. Enjoy.
   */
  static {
    final ReportingSystemStartupListener startup = new ReportingSystemStartupListener();
    startup.startup( null );
  }

  /**
   * The output-type for the generated report, such as PDF, XLS, CSV, HTML, etc This must be the mime-type!
   */
  private String outputType;
  private String outputTarget;
  private String defaultOutputTarget;
  private MasterReport report;
  private Map<String, Object> inputs;
  private OutputStream outputStream;
  private InputStream reportDefinitionInputStream;
  private IActionSequenceResource reportDefinition;
  private String reportDefinitionPath;
  private boolean paginateOutput = false;
  private int acceptedPage;
  private int pageCount;
  private boolean dashboardMode;
  private Boolean useJcr;
  private String jcrOutputPath;
  protected ReportContentUtil reportContentUtil;

  /*
   * These fields are for enabling printing
   */
  private boolean print = false;
  private String printer;

  /*
   * Default constructor
   */
  public SimpleReportingAction() {
    this.inputs = Collections.emptyMap();
    acceptedPage = -1;
    pageCount = -1;
    defaultOutputTarget = HtmlTableModule.TABLE_HTML_STREAM_EXPORT_TYPE;
    useJcr = Boolean.FALSE;
    reportContentUtil = new ReportContentUtil();
  }

  // ----------------------------------------------------------------------------
  // BEGIN BEAN METHODS
  // ----------------------------------------------------------------------------

  public String getDefaultOutputTarget() {
    return defaultOutputTarget;
  }

  public void setDefaultOutputTarget( final String defaultOutputTarget ) {
    if ( defaultOutputTarget == null ) {
      throw new NullPointerException();
    }
    this.defaultOutputTarget = defaultOutputTarget;
  }

  public String getOutputTarget() {
    return outputTarget;
  }

  public void setOutputTarget( final String outputTarget ) {
    this.outputTarget = outputTarget;
  }

  /**
   * Sets the mime-type for determining which report output type to generate. This should be a mime-type for consistency
   * with streaming output mime-types.
   * 
   * @param outputType
   *          the desired output type (mime-type) for the report engine to generate
   */
  public void setOutputType( final String outputType ) {
    this.outputType = outputType;
  }

  /**
   * Gets the output type, this should be a mime-type for consistency with streaming output mime-types.
   * 
   * @return the current output type for the report
   */
  public String getOutputType() {
    return outputType;
  }

  /**
   * This method returns the resource for the report-definition, if available.
   * 
   * @return the report-definition resource
   */
  public IActionSequenceResource getReportDefinition() {
    return reportDefinition;
  }

  /**
   * Sets the report-definition if it is provided to us by way of an action-sequence resource. The name must be
   * reportDefinition or report-definition.
   * 
   * @param reportDefinition
   *          a report-definition as seen (wrapped) by an action-sequence
   */
  public void setReportDefinition( final IActionSequenceResource reportDefinition ) {
    this.reportDefinition = reportDefinition;
  }

  /**
   * This method will be called if an input is called reportDefinitionInputStream, or any variant of that with dashes
   * report-definition-inputstream for example. The primary purpose of this method is to facilitate unit testing.
   * 
   * @param reportDefinitionInputStream
   *          any kind of InputStream which contains a valid report-definition
   */
  public void setInputStream( InputStream reportDefinitionInputStream ) {
    this.reportDefinitionInputStream = reportDefinitionInputStream;
  }

  /**
   * Returns the path to the report definition (for platform use this is a path in the solution repository)
   * 
   * @return reportdefinitionPath
   */
  public String getReportDefinitionPath() {
    return reportDefinitionPath;
  }

  /**
   * Sets the path to the report definition (platform path)
   * 
   * @param reportDefinitionPath
   *          the path to the report definition.
   */
  public void setReportDefinitionPath( String reportDefinitionPath ) {
    this.reportDefinitionPath = reportDefinitionPath;
  }

  /**
   * Returns true if the report engine will be asked to use a paginated (HTML) output processor
   * 
   * @return paginated
   */
  public boolean isPaginateOutput() {
    return paginateOutput;
  }

  /**
   * Set the paging mode used by the reporting engine. This will also be set if an input
   * 
   * @param paginateOutput
   *          page mode
   */
  public void setPaginateOutput( final boolean paginateOutput ) {
    this.paginateOutput = paginateOutput;
  }

  public int getAcceptedPage() {
    return acceptedPage;
  }

  public void setAcceptedPage( final int acceptedPage ) {
    this.acceptedPage = acceptedPage;
  }

  public boolean isDashboardMode() {
    return dashboardMode;
  }

  public void setDashboardMode( final boolean dashboardMode ) {
    this.dashboardMode = dashboardMode;
  }

  /**
   * Gets the useContentRepository flag, needed by subclasses, such as with interactive adhoc
   * 
   * @return useContentRepository
   */
  public boolean getUseJCR() {
    return useJcr;
  }

  public void setUseJcr( final Boolean useJcr ) {
    this.useJcr = useJcr;
  }

  public String getJcrOutputPath() {
    return jcrOutputPath;
  }

  public void setJcrOutputPath( String jcrOutputPath ) {
    this.jcrOutputPath = jcrOutputPath;
  }

  public String getMimeType( String ignored ) {
    return getMimeType();
  }

  /**
   * This method returns the mime-type for the streaming output based on the effective output target.
   * 
   * @return the mime-type for the streaming output
   * @see SimpleReportingComponent#computeEffectiveOutputTarget()
   */
  public String getMimeType() {
    try {
      final String outputTarget = computeEffectiveOutputTarget();
      if ( log.isDebugEnabled() ) {
        log.debug( Messages.getInstance().getString( "ReportPlugin.logComputedOutputTarget", outputTarget ) );
      }

      ReportOutputHandlerFactory handlerFactory = PentahoSystem.get( ReportOutputHandlerFactory.class );
      if ( handlerFactory == null ) {
        handlerFactory = new FastExportReportOutputHandlerFactory();
      }

      return handlerFactory.getMimeType( new InternalOutputHandlerSelector( outputTarget ) );

    } catch ( IOException e ) {
      if ( log.isDebugEnabled() ) {
        log.warn( Messages.getInstance().getString( "ReportPlugin.logErrorMimeTypeFull" ), e );
      } else if ( log.isWarnEnabled() ) {
        log.warn( Messages.getInstance().getString( "ReportPlugin.logErrorMimeTypeShort", e.getMessage() ) );
      }
      return MIME_GENERIC_FALLBACK;
    } catch ( ResourceException e ) {
      if ( log.isDebugEnabled() ) {
        log.warn( Messages.getInstance().getString( "ReportPlugin.logErrorMimeTypeFull" ), e );
      } else if ( log.isWarnEnabled() ) {
        log.warn( Messages.getInstance().getString( "ReportPlugin.logErrorMimeTypeShort", e.getMessage() ) );
      }
      return MIME_GENERIC_FALLBACK;
    }
  }

  /**
   * This method sets the OutputStream to write streaming content on.
   * 
   * @param outputStream
   *          an OutputStream to write to
   */
  public void setOutputStream( final OutputStream outputStream ) {
    this.outputStream = outputStream;
  }

  /**
   * This method checks if the output is targeting a printer
   * 
   * @return true if the output is supposed to go to a printer
   */
  public boolean isPrint() {
    return print;
  }

  /**
   * Set whether or not to send the report to a printer
   * 
   * @param print
   *          a flag indicating whether the report should be printed.
   */
  public void setPrint( final boolean print ) {
    this.print = print;
  }

  /**
   * This method gets the name of the printer the report will be sent to
   * 
   * @return the name of the printer that the report will be sent to
   */
  public String getPrinter() {
    return printer;
  }

  /**
   * Set the name of the printer to send the report to
   * 
   * @param printer
   *          the name of the printer that the report will be sent to, a null value will be interpreted as the default
   *          printer
   */
  public void setPrinter( final String printer ) {
    this.printer = printer;
  }

  /**
   * Get the inputs, needed by subclasses, such as with interactive adhoc
   * 
   * @return immutable input map
   */
  public Map<String, Object> getInputs() {
    if ( inputs != null ) {
      return Collections.unmodifiableMap( inputs );
    }
    return Collections.emptyMap();
  }

  /**
   * This method sets the map of *all* the inputs which are available to this component. This allows us to use
   * action-sequence inputs as parameters for our reports.
   * 
   * @param inputs
   *          a Map containing inputs
   */
  public void setVarArgs( Map<String, Object> inputs ) {
    if ( inputs == null ) {
      this.inputs = Collections.emptyMap();
      return;
    }

    this.inputs = inputs;
  }

  // ----------------------------------------------------------------------------
  // END BEAN METHODS
  // ----------------------------------------------------------------------------

  protected Object getInput( final String key, final Object defaultValue ) {
    if ( inputs != null ) {
      final Object input = inputs.get( key );
      if ( input != null ) {
        return input;
      }
    }
    return defaultValue;
  }

  /**
   * Sets the MasterReport for the report-definition, needed by subclasses, such as with interactive adhoc
   * 
   * @return nothing
   */
  public void setReport( MasterReport report ) {
    this.report = report;
    final String clText = extractContentLinkSpec();
    report.setReportEnvironment( new PentahoReportEnvironment( report.getConfiguration(), clText, SimpleReportingComponent.getReportPath( report.getContentBase() ) ) );
  }

  /**
   * Get the MasterReport for the report-definition, the MasterReport object will be cached as needed, using the
   * PentahoResourceLoader.
   * 
   * @return a parsed MasterReport object
   * @throws ResourceException
   * @throws IOException
   */
  public MasterReport getReport() throws ResourceException, IOException {
    if ( report == null ) {
      if ( reportDefinitionInputStream != null ) {
        if ( reportDefinitionInputStream instanceof RepositoryFileInputStream ) {
          RepositoryFileInputStream repositoryFileInputStream = (RepositoryFileInputStream) reportDefinitionInputStream;
          RepositoryFile repoFile = repositoryFileInputStream.getFile();
          if ( repoFile != null ) {
            Serializable fileId = repoFile.getId();
            if ( fileId != null ) {
              report = ReportCreator.createReport( fileId );
            }
          }
        }
        if ( report == null ) {
          report = ReportCreator.createReport( reportDefinitionInputStream, getDefinedResourceURL( null ) );
        }
      } else if ( reportDefinition != null ) {
        // load the report definition as an action-sequence resource
        report = ReportCreator.createReport( reportDefinition.getAddress() );
      } else if ( reportDefinitionPath != null ) {
        report = ReportCreator.createReportByName( reportDefinitionPath );
      } else {
        throw new ResourceException();
      }

      final String clText = extractContentLinkSpec();
      report.setReportEnvironment( new PentahoReportEnvironment( report.getConfiguration(), clText, SimpleReportingComponent.getReportPath( report.getContentBase() ) ) );
    }
    try {
      // force autoSubmit flag (based on settings.xml)?
      IPluginManager pm = PentahoSystem.get( IPluginManager.class );
      if ( pm != null ) {
        Object autoSubmitSetting = pm.getPluginSetting( "reporting", "settings/auto-submit", null );
        if ( autoSubmitSetting != null ) {
          boolean autoSubmit = Boolean.parseBoolean( autoSubmitSetting.toString() );
          report.setAttribute( AttributeNames.Core.NAMESPACE, AttributeNames.Core.AUTO_SUBMIT_PARAMETER, autoSubmit );
        }
        Object autoSubmitDefaultSetting = pm.getPluginSetting( "reporting", "settings/auto-submit-default", null );
        if ( autoSubmitDefaultSetting != null ) {
          boolean autoSubmitDefault = Boolean.parseBoolean( autoSubmitDefaultSetting.toString() );
          report.setAttribute( AttributeNames.Core.NAMESPACE, AttributeNames.Core.AUTO_SUBMIT_DEFAULT,
            autoSubmitDefault );
        }
      }
    } catch ( Throwable t ) {
      log.warn( t.getMessage(), t );
    }
    report.setQueryLimit( -1 );
    return report;
  }

  private String extractContentLinkSpec() {
    if ( inputs == null ) {
      return null;
    }

    final Object clRaw = inputs.get( ParameterXmlContentHandler.SYS_PARAM_CONTENT_LINK );
    if ( clRaw == null ) {
      return null;
    }

    if ( clRaw instanceof Collection ) {
      final Collection<?> c = (Collection<?>) clRaw;
      final CSVQuoter quoter = new CSVQuoter( ',', '"' );
      final StringBuilder b = new StringBuilder();
      for ( final Object o : c ) {
        final String s = quoter.doQuoting( String.valueOf( o ) );
        if ( b.length() > 0 ) {
          b.append( ',' );
        }
        b.append( s );
      }
      return b.toString();
    }

    if ( clRaw.getClass().isArray() ) {
      final CSVQuoter quoter = new CSVQuoter( ',', '"' );
      final StringBuilder b = new StringBuilder();
      for ( int i = 0, size = Array.getLength( clRaw ); i < size; i++ ) {
        final Object o = Array.get( clRaw, i );
        final String s = quoter.doQuoting( String.valueOf( o ) );
        if ( b.length() > 0 ) {
          b.append( ',' );
        }
        b.append( s );
      }
      return b.toString();
    } else {
      return String.valueOf( clRaw );
    }
  }

  private boolean isValidOutputType( final String outputType ) {
    if ( PNG_EXPORT_TYPE.equals( outputType ) ) {
      return true;
    }
    return ReportProcessTaskRegistry.getInstance().isExportTypeRegistered( outputType );
  }

  /**
   * Computes the effective output target that will be used when running the report. This method does not modify any of
   * the properties of this class.
   * <p/>
   * The algorithm to determine the output target is as follows:
   * <ul>
   * <li>
   * If the report attribute "lock-preferred-output-type" is set, and the attribute preferred-output-type is set, the
   * report will always be exported to the specified output type.</li>
   * <li>If the component has the parameter "output-target" set, this output target will be used.</li>
   * <li>If the component has the parameter "output-type" set, the mime-type will be translated into a suitable output
   * target (depends on other parameters like paginate as well.</li>
   * <li>If neither output-target or output-type are specified, the report's preferred output type will be used.</li>
   * <li>If no preferred output type is set, we default to HTML export.</li>
   * </ul>
   * <p/>
   * If the output type given is invalid, the report will not be executed and calls to
   * <code>SimpleReportingComponent#getMimeType()</code> will yield the generic "application/octet-stream" response.
   * 
   * @return
   * @throws IOException
   * @throws ResourceException
   */
  private String computeEffectiveOutputTarget() throws IOException, ResourceException {
    final MasterReport report = getReport();
    if ( Boolean.TRUE.equals( report.getAttribute( AttributeNames.Core.NAMESPACE,
      AttributeNames.Core.LOCK_PREFERRED_OUTPUT_TYPE ) ) ) {
      // preferred output type is one of the engine's output-target identifiers. It is not a mime-type string.
      // The engine supports multiple subformats per mime-type (example HTML: zipped/streaming/flow/pageable)
      // The mime-mapping would be inaccurate.
      final Object preferredOutputType =
        report.getAttribute( AttributeNames.Core.NAMESPACE, AttributeNames.Core.PREFERRED_OUTPUT_TYPE );
      if ( preferredOutputType != null ) {
        final String preferredOutputTypeString = String.valueOf( preferredOutputType );
        if ( isValidOutputType( preferredOutputTypeString ) ) {
          // if it is a recognized process-type, then fine, return it.
          return preferredOutputTypeString;
        }

        final String mappedLegacyType = mapOutputTypeToOutputTarget( preferredOutputTypeString );
        if ( mappedLegacyType != null ) {
          log.warn( Messages.getInstance().getString( "ReportPlugin.warnLegacyLockedOutput",
            preferredOutputTypeString ) );
          return mappedLegacyType;
        }

        log.warn( Messages.getInstance().getString( "ReportPlugin.warnInvalidLockedOutput",
          preferredOutputTypeString ) );
      }
    }

    final String outputTarget = getOutputTarget();
    if ( outputTarget != null ) {
      if ( isValidOutputType( outputTarget ) == false ) {
        log.warn( Messages.getInstance().getString( "ReportPlugin.warnInvalidOutputTarget", outputTarget ) );
      }
      // if a engine-level output target is given, use it as it is. We can assume that the user knows how to
      // map from that to a real mime-type.
      return outputTarget;
    }

    final String mappingFromParams = mapOutputTypeToOutputTarget( getOutputType() );
    if ( mappingFromParams != null ) {
      return mappingFromParams;
    }

    // if nothing is specified explicity, we may as well ask the report what it prefers..
    final Object preferredOutputType =
      report.getAttribute( AttributeNames.Core.NAMESPACE, AttributeNames.Core.PREFERRED_OUTPUT_TYPE );
    if ( preferredOutputType != null ) {
      final String preferredOutputTypeString = String.valueOf( preferredOutputType );
      if ( isValidOutputType( preferredOutputTypeString ) ) {
        return preferredOutputTypeString;
      }

      final String mappedLegacyType = mapOutputTypeToOutputTarget( preferredOutputTypeString );
      if ( mappedLegacyType != null ) {
        log.warn( Messages.getInstance()
          .getString( "ReportPlugin.warnLegacyPreferredOutput", preferredOutputTypeString ) );
        return mappedLegacyType;
      }

      log.warn( Messages.getInstance().getString( "ReportPlugin.warnInvalidPreferredOutput", preferredOutputTypeString,
        getDefaultOutputTarget() ) );
      return getDefaultOutputTarget();
    }

    if ( StringUtils.isEmpty( getOutputTarget() ) == false || StringUtils.isEmpty( getOutputType() ) == false ) {
      // if you have come that far, it means you really messed up. Sorry, this error is not a error caused
      // by our legacy code - it is more likely that you just entered values that are totally wrong.
      log.error( Messages.getInstance().getString( "ReportPlugin.warnInvalidOutputType", getOutputType(),
        getDefaultOutputTarget() ) );
    }
    return getDefaultOutputTarget();
  }

  public String getComputedOutputTarget() throws IOException, ResourceException {
    return computeEffectiveOutputTarget();
  }

  private String mapOutputTypeToOutputTarget( final String outputType ) {
    // if the user has given a mime-type instead of a output-target, lets map it to the "best" choice. If the
    // user wanted full control, he would have used the output-target property instead.
    if ( MIME_TYPE_CSV.equals( outputType ) ) {
      return CSVTableModule.TABLE_CSV_STREAM_EXPORT_TYPE;
    }
    if ( MIME_TYPE_HTML.equals( outputType ) ) {
      if ( isPaginateOutput() ) {
        return HtmlTableModule.TABLE_HTML_PAGE_EXPORT_TYPE;
      }
      return HtmlTableModule.TABLE_HTML_STREAM_EXPORT_TYPE;
    }
    if ( MIME_TYPE_XML.equals( outputType ) ) {
      if ( isPaginateOutput() ) {
        return XmlTableModule.TABLE_XML_EXPORT_TYPE;
      }
      return XmlPageableModule.PAGEABLE_XML_EXPORT_TYPE;
    }
    if ( MIME_TYPE_PDF.equals( outputType ) ) {
      return PdfPageableModule.PDF_EXPORT_TYPE;
    }
    if ( MIME_TYPE_RTF.equals( outputType ) ) {
      return RTFTableModule.TABLE_RTF_FLOW_EXPORT_TYPE;
    }
    if ( MIME_TYPE_XLS.equals( outputType ) ) {
      return ExcelTableModule.EXCEL_FLOW_EXPORT_TYPE;
    }
    if ( MIME_TYPE_XLSX.equals( outputType ) ) {
      return ExcelTableModule.XLSX_FLOW_EXPORT_TYPE;
    }
    if ( MIME_TYPE_EMAIL.equals( outputType ) ) {
      return MIME_TYPE_EMAIL;
    }
    if ( MIME_TYPE_TXT.equals( outputType ) ) {
      return PlainTextPageableModule.PLAINTEXT_EXPORT_TYPE;
    }

    if ( "pdf".equalsIgnoreCase( outputType ) ) {
      log.warn( Messages.getInstance().getString( "ReportPlugin.warnDeprecatedPDF" ) );
      return PdfPageableModule.PDF_EXPORT_TYPE;
    } else if ( "html".equalsIgnoreCase( outputType ) ) {
      log.warn( Messages.getInstance().getString( "ReportPlugin.warnDeprecatedHTML" ) );
      if ( isPaginateOutput() ) {
        return HtmlTableModule.TABLE_HTML_PAGE_EXPORT_TYPE;
      }
      return HtmlTableModule.TABLE_HTML_STREAM_EXPORT_TYPE;
    } else if ( "csv".equalsIgnoreCase( outputType ) ) {
      log.warn( Messages.getInstance().getString( "ReportPlugin.warnDeprecatedCSV" ) );
      return CSVTableModule.TABLE_CSV_STREAM_EXPORT_TYPE;
    } else if ( "rtf".equalsIgnoreCase( outputType ) ) {
      log.warn( Messages.getInstance().getString( "ReportPlugin.warnDeprecatedRTF" ) );
      return RTFTableModule.TABLE_RTF_FLOW_EXPORT_TYPE;
    } else if ( "xls".equalsIgnoreCase( outputType ) ) {
      log.warn( Messages.getInstance().getString( "ReportPlugin.warnDeprecatedXLS" ) );
      return ExcelTableModule.EXCEL_FLOW_EXPORT_TYPE;
    } else if ( "txt".equalsIgnoreCase( outputType ) ) {
      log.warn( Messages.getInstance().getString( "ReportPlugin.warnDeprecatedTXT" ) );
      return PlainTextPageableModule.PLAINTEXT_EXPORT_TYPE;
    }
    return null;
  }

  /**
   * Apply inputs (if any) to corresponding report parameters, care is taken when checking parameter types to perform
   * any necessary casting and conversion.
   * 
   * @param report
   *          a MasterReport object to apply parameters to
   * @param context
   *          a ParameterContext for which the parameters will be under
   * @deprecated use the single parameter version instead. This method will now fail with an error if the report passed
   *             in is not the same as the report this component has. This method will be removed in version 4.0.
   */
  public void applyInputsToReportParameters( final MasterReport report, final ParameterContext context ) {
    try {
      if ( getReport() != report ) {
        throw new IllegalStateException( Messages.getInstance().getString( "ReportPlugin.errorForeignReportInput" ) );
      }
      final ValidationResult validationResult = applyInputsToReportParameters( context, null );
      if ( validationResult.isEmpty() == false ) {
        throw new IllegalStateException( Messages.getInstance().getString( "ReportPlugin.errorApplyInputsFailed" ) );
      }
    } catch ( IOException e ) {
      throw new IllegalStateException( Messages.getInstance().getString( "ReportPlugin.errorApplyInputsFailed" ), e );
    } catch ( ResourceException e ) {
      throw new IllegalStateException( Messages.getInstance().getString( "ReportPlugin.errorApplyInputsFailed" ), e );
    }
  }

  /**
   * Apply inputs (if any) to corresponding report parameters, care is taken when checking parameter types to perform
   * any necessary casting and conversion.
   * 
   * @param context
   *          a ParameterContext for which the parameters will be under
   * @param validationResult
   *          the validation result that will hold the warnings. If null, a new one will be created.
   * @return the validation result containing any parameter validation errors.
   * @throws java.io.IOException
   *           if the report of this component could not be parsed.
   * @throws ResourceException
   *           if the report of this component could not be parsed.
   */
  public ValidationResult applyInputsToReportParameters( final ParameterContext context,
                                                         ValidationResult validationResult )
    throws IOException, ResourceException {
    if ( validationResult == null ) {
      validationResult = new ValidationResult();
    }
    // apply inputs to report
    if ( inputs != null ) {
      final MasterReport report = getReport();
      final ParameterDefinitionEntry[] params = report.getParameterDefinition().getParameterDefinitions();
      final ReportParameterValues parameterValues = report.getParameterValues();
      for ( final ParameterDefinitionEntry param : params ) {
        final String paramName = param.getName();
        try {
          final Object computedParameter =
            reportContentUtil.computeParameterValue( context, param, inputs.get( paramName ) );
          parameterValues.put( param.getName(), computedParameter );
          if ( log.isInfoEnabled() ) {
            log.info( Messages.getInstance().getString( "ReportPlugin.infoParameterValues", paramName,
              String.valueOf( inputs.get( paramName ) ), String.valueOf( computedParameter ) ) );
          }
        } catch ( Exception e ) {
          if ( log.isWarnEnabled() ) {
            log.warn( Messages.getInstance().getString( "ReportPlugin.logErrorParametrization" ), e );
          }
          validationResult.addError( paramName, new ValidationMessage( e.getMessage() ) );
        }
      }
    }
    return validationResult;
  }

  private URL getDefinedResourceURL( final URL defaultValue ) {
    if ( inputs == null || inputs.containsKey( REPORTLOAD_RESURL ) == false ) {
      return defaultValue;
    }

    try {
      final String inputStringValue = (String) getInput( REPORTLOAD_RESURL, null );
      return new URL( inputStringValue );
    } catch ( Exception e ) {
      return defaultValue;
    }
  }

  protected int getYieldRate() {
    final Object yieldRate = getInput( REPORTGENERATE_YIELDRATE, null );
    if ( yieldRate instanceof Number ) {
      final Number n = (Number) yieldRate;
      if ( n.intValue() < 1 ) {
        return 0;
      }
      return n.intValue();
    }
    return 0;
  }

  /**
   * This method returns the number of logical pages which make up the report. This results of this method are available
   * only after validate/execute have been successfully called. This field has no setter, as it should never be set by
   * users.
   * 
   * @return the number of logical pages in the report
   */
  public int getPageCount() {
    return pageCount;
  }

  /**
   * This method will determine if the component instance 'is valid.' The validate() is called after all of the bean
   * 'setters' have been called, so we may validate on the actual values, not just the presence of inputs as we were
   * historically accustomed to.
   * <p/>
   * Since we should have a list of all action-sequence inputs, we can determine if we have sufficient inputs to meet
   * the parameter requirements of the report-definition. This would include validation of values and ranges of values.
   * 
   * @return true if valid
   * @throws Exception
   */
  public boolean validate() throws Exception {
    if ( reportDefinition == null && reportDefinitionInputStream == null && reportDefinitionPath == null ) {
      log.error( Messages.getInstance().getString( "ReportPlugin.reportDefinitionNotProvided" ) );
      return false;
    }
    if ( outputStream == null && print == false ) {
      log.error( Messages.getInstance().getString( "ReportPlugin.outputStreamRequired" ) );
      return false;
    }
    if ( inputs == null ) {
      log.error( Messages.getInstance().getString( "ReportPlugin.inputParameterRequired" ) );
      return false;
    }
    return true;
  }

  public void execute() throws Exception {
    if ( !_execute() ) {
      throw new Exception( "execution failed for an unspecified reason" );
    }
  }

  /**
   * Perform the primary function of this component, this is, to execute. This method will be invoked immediately
   * following a successful validate().
   * 
   * @return true if successful execution
   * @throws Exception
   */
  public boolean _execute() throws Exception {
    if ( inputs != null ) {
      if ( StringUtils.isEmpty( outputTarget ) == false && outputTarget.equals( "table/html;page-mode=page" ) ) {
        setPaginateOutput( true );
        inputs.put( "output-target", getOutputTarget() );
        final Configuration configuration = report.getConfiguration();
        if ( configuration instanceof ModifiableConfiguration ) {
          final ModifiableConfiguration modifiableConfiguration = (ModifiableConfiguration) configuration;
          modifiableConfiguration.setConfigProperty( PentahoPlatformModule.FORCE_ALL_PAGES, "true" );
        }
      }
    }
    // SP-307, BISERVER-9688

    final MasterReport report = getReport();
    int yieldRate = getYieldRate();
    if ( yieldRate > 0 ) {
      report.getReportConfiguration().setConfigProperty(
        "org.pentaho.reporting.engine.classic.core.YieldRate", String.valueOf( yieldRate ) );
    }

    try {
      final DefaultParameterContext parameterContext = new DefaultParameterContext( report );
      // open parameter context
      final ValidationResult vr = applyInputsToReportParameters( parameterContext, null );
      if ( vr.isEmpty() == false ) {
        return false;
      }
      parameterContext.close();

      if ( isPrint() ) {
        // handle printing
        // basic logic here is: get the default printer, attempt to resolve the user specified printer, default back as
        // needed
        PrintService printService = PrintServiceLookup.lookupDefaultPrintService();
        if ( StringUtils.isEmpty( getPrinter() ) == false ) {
          final PrintService[] services =
            PrintServiceLookup.lookupPrintServices( DocFlavor.SERVICE_FORMATTED.PAGEABLE, null );
          for ( final PrintService service : services ) {
            if ( service.getName().equals( printer ) ) {
              printService = service;
            }
          }
          if ( ( printer == null ) && ( services.length > 0 ) ) {
            printService = services[ 0 ];
          }
        }
        Java14PrintUtil.printDirectly( report, printService );
        return true;
      }

      final String outputType = computeEffectiveOutputTarget();
      final ReportOutputHandler reportOutputHandler = createOutputHandlerForOutputType( outputType );
      if ( reportOutputHandler == null ) {
        log.warn( Messages.getInstance().getString( "ReportPlugin.warnUnprocessableRequest", outputType ) );
        return false;
      }
      synchronized ( reportOutputHandler.getReportLock() ) {
        try {
          // if the report is executed in background (scheduled) and it's execution is to be logged we need to set the requestId to the lineage-id
          if ( ReportListenerThreadHolder.getRequestId() == null && this.inputs.get( RESERVEDMAPKEY_LINEAGE_ID ) != null && !this.inputs.get( RESERVEDMAPKEY_LINEAGE_ID ).toString().equals( "" ) ) {
            ReportListenerThreadHolder.setRequestId( this.inputs.get( RESERVEDMAPKEY_LINEAGE_ID ).toString() );
          }
          pageCount = reportOutputHandler.generate( report, acceptedPage, outputStream, getYieldRate() );
          return pageCount != -1;
        } finally {
          reportOutputHandler.close();
        }
      }
    } catch ( Throwable t ) {
      log.error( Messages.getInstance().getString( "ReportPlugin.executionFailed" ), t );
    }
    // lets not pretend we were successfull, if the export type was not a valid one.
    return false;
  }

  protected ReportOutputHandler createOutputHandlerForOutputType( final String outputType ) throws IOException {
    if ( inputs == null ) {
      throw new IllegalStateException( "Inputs are null, this component did not validate properly" );
    }

    final Object attribute =
      report.getAttribute( AttributeNames.Pentaho.NAMESPACE, AttributeNames.Pentaho.REPORT_CACHE );
    final ReportCacheKey reportCacheKey = new ReportCacheKey( getViewerSessionId(), inputs );
    ReportCache cache;
    if ( Boolean.FALSE.equals( attribute ) ) {
      cache = new NullReportCache();
    } else {
      cache = PentahoSystem.get( ReportCache.class );
      if ( cache == null ) {
        cache = new DefaultReportCache();
      }
      final ReportOutputHandler outputHandler = cache.get( reportCacheKey );
      if ( outputHandler != null ) {
        return outputHandler;
      }
    }

    if ( dashboardMode ) {
      report.getReportConfiguration().setConfigProperty( HtmlTableModule.BODY_FRAGMENT, "true" );
    }

    ReportOutputHandlerFactory handlerFactory = PentahoSystem.get( ReportOutputHandlerFactory.class );
    if ( handlerFactory == null ) {
      handlerFactory = new FastExportReportOutputHandlerFactory();
    }

    ReportOutputHandler reportOutputHandler =
      handlerFactory.createOutputHandlerForOutputType( new InternalOutputHandlerSelector( outputType ) );
    if ( reportOutputHandler == null ) {
      return null;
    }
    return cache.put( reportCacheKey, reportOutputHandler );
  }

  /**
   * Perform a pagination run.
   * 
   * @return the number of pages or streams generated.
   * @throws IOException
   *           if an IO error occurred while loading the report.
   * @throws ResourceException
   *           if a resource loading error occurred.
   */
  public int paginate() throws IOException, ResourceException {
    final MasterReport report = getReport();

    try {
      final ParameterContext parameterContext = new DefaultParameterContext( report );
      // open parameter context
      final ValidationResult vr = applyInputsToReportParameters( parameterContext, null );
      if ( vr.isEmpty() == false ) {
        return 0;
      }

      parameterContext.close();

      if ( isPrint() ) {
        return 0;
      }

      final String outputType = computeEffectiveOutputTarget();
      final ReportOutputHandler reportOutputHandler = createOutputHandlerForOutputType( outputType );
      if ( reportOutputHandler == null ) {
        log.warn( Messages.getInstance().getString( "ReportPlugin.warnUnprocessableRequest", outputType ) );
        return 0;
      }
      synchronized ( reportOutputHandler.getReportLock() ) {
        try {
          return reportOutputHandler.paginate( report, getYieldRate() );
        } finally {
          reportOutputHandler.close();
        }
      }
    } catch ( Throwable t ) {
      log.error( Messages.getInstance().getString( "ReportPlugin.executionFailed" ), t );
    }
    // lets not pretend we were successfull, if the export type was not a valid one.
    return 0;
  }

  protected String getViewerSessionId() {
    if ( inputs == null ) {
      return null;
    }
    final Object o = inputs.get( ParameterXmlContentHandler.SYS_PARAM_SESSION_ID );
    if ( o instanceof String ) {
      return String.valueOf( o );
    }
    return null;
  }

  private class InternalOutputHandlerSelector implements ReportOutputHandlerSelector {
    private String outputType;

    private InternalOutputHandlerSelector( final String outputType ) {
      this.outputType = outputType;
    }

    public String getOutputType() {
      return outputType;
    }

    public MasterReport getReport() {
      return report;
    }

    public boolean isUseJcrOutput() {
      return Boolean.TRUE.equals( SimpleReportingAction.this.getUseJCR() );
    }

    public String getJcrOutputPath() {
      return SimpleReportingAction.this.getJcrOutputPath();
    }

    public <T> T getInput( final String parameterName, final T defaultValue, final Class<T> idx ) {
      Object input = SimpleReportingAction.this.getInput( parameterName, defaultValue );
      if ( input == null ) {
        input = defaultValue;
      }
      return idx.cast( input );
    }
  }

}

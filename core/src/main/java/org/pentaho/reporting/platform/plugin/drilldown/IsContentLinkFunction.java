/*! ******************************************************************************
 *
 * Pentaho
 *
 * Copyright (C) 2024 by Hitachi Vantara, LLC : http://www.pentaho.com
 *
 * Use of this software is governed by the Business Source License included
 * in the LICENSE.TXT file.
 *
 * Change Date: 2029-07-20
 ******************************************************************************/


package org.pentaho.reporting.platform.plugin.drilldown;

import java.util.HashSet;
import java.util.Set;

import org.pentaho.reporting.engine.classic.core.ReportEnvironment;
import org.pentaho.reporting.engine.classic.core.function.ReportFormulaContext;
import org.pentaho.reporting.engine.classic.core.modules.output.table.html.HtmlTableModule;
import org.pentaho.reporting.libraries.base.util.CSVTokenizer;
import org.pentaho.reporting.libraries.formula.EvaluationException;
import org.pentaho.reporting.libraries.formula.FormulaContext;
import org.pentaho.reporting.libraries.formula.LibFormulaErrorValue;
import org.pentaho.reporting.libraries.formula.function.Function;
import org.pentaho.reporting.libraries.formula.function.ParameterCallback;
import org.pentaho.reporting.libraries.formula.lvalues.TypeValuePair;
import org.pentaho.reporting.libraries.formula.typing.ArrayCallback;
import org.pentaho.reporting.libraries.formula.typing.Type;
import org.pentaho.reporting.libraries.formula.typing.coretypes.LogicalType;

public class IsContentLinkFunction implements Function {
  public IsContentLinkFunction() {
  }

  public String getCanonicalName() {
    return "ISCONTENTLINK";
  }

  public TypeValuePair evaluate( final FormulaContext context, final ParameterCallback parameters )
    throws EvaluationException {
    if ( parameters.getParameterCount() != 1 ) {
      throw EvaluationException.getInstance( LibFormulaErrorValue.ERROR_ARGUMENTS_VALUE );
    }

    final HashSet<String> params = new HashSet<String>();
    final Object o = parameters.getValue( 0 );
    if ( o instanceof Object[][] ) {
      final Object[][] o2 = (Object[][]) o;
      for ( int i = 0; i < o2.length; i++ ) {
        final Object[] values = o2[ i ];
        if ( values == null || values.length == 0 ) {
          throw EvaluationException.getInstance( LibFormulaErrorValue.ERROR_ILLEGAL_ARRAY_VALUE );
        }
        params.add( String.valueOf( values[ 0 ] ) );
      }
    } else {
      final Type type = parameters.getType( 0 );
      final ArrayCallback callback = context.getTypeRegistry().convertToArray( type, o );
      if ( callback.getColumnCount() != 2 ) {
        throw EvaluationException.getInstance( LibFormulaErrorValue.ERROR_ILLEGAL_ARRAY_VALUE );
      }
      for ( int i = 0, n = callback.getRowCount(); i < n; i++ ) {
        params.add( String.valueOf( callback.getValue( i, 0 ) ) );
      }
    }

    return new TypeValuePair( LogicalType.TYPE, isContentLink( context, params ) );
  }

  private Boolean isContentLink( final FormulaContext context, final Set<String> parameters ) {
    if ( ( context instanceof ReportFormulaContext ) == false ) {
      return Boolean.FALSE;
    }

    final ReportFormulaContext reportFormulaContext = (ReportFormulaContext) context;
    final String exportType = reportFormulaContext.getExportType();
    if ( exportType.startsWith( "table/html" ) == false || HtmlTableModule.ZIP_HTML_EXPORT_TYPE.equals( exportType ) ) {
      return Boolean.FALSE;
    }

    final ReportEnvironment environment = reportFormulaContext.getRuntime().getProcessingContext().getEnvironment();
    final Object clText = environment.getEnvironmentProperty( "contentLink" );
    if ( clText == null ) {
      return Boolean.FALSE;
    }
    final CSVTokenizer csvTokenizer = new CSVTokenizer( String.valueOf( clText ), ",", "\"" );
    while ( csvTokenizer.hasMoreTokens() ) {
      final String el = csvTokenizer.nextToken();
      if ( parameters.contains( el ) ) {
        return Boolean.TRUE;
      }
    }
    return Boolean.FALSE;
  }
}

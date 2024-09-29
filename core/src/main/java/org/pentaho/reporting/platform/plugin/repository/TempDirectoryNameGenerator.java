/*! ******************************************************************************
 *
 * Pentaho
 *
 * Copyright (C) 2024 by Hitachi Vantara, LLC : http://www.pentaho.com
 *
 * Use of this software is governed by the Business Source License included
 * in the LICENSE.TXT file.
 *
 * Change Date: 2028-08-13
 ******************************************************************************/


package org.pentaho.reporting.platform.plugin.repository;

import java.io.File;
import java.io.IOException;

import org.pentaho.platform.engine.core.system.PentahoSystem;
import org.pentaho.platform.api.engine.IPentahoSession;
import org.pentaho.platform.engine.core.system.PentahoSessionHolder;
import org.pentaho.platform.api.engine.IApplicationContext;

import org.pentaho.reporting.libraries.repository.ContentIOException;
import org.pentaho.reporting.libraries.repository.ContentLocation;
import org.pentaho.reporting.libraries.repository.DefaultNameGenerator;
import org.pentaho.reporting.libraries.repository.MimeRegistry;
import org.pentaho.reporting.libraries.repository.file.FileContentLocation;

public class TempDirectoryNameGenerator implements PentahoNameGenerator {
  private File targetDirectory;
  private MimeRegistry mimeRegistry;
  private DefaultNameGenerator fallback;
  private boolean safeToDelete;

  public TempDirectoryNameGenerator() {
  }

  public void initialize( final ContentLocation contentLocation, final boolean safeToDelete ) {
    this.safeToDelete = safeToDelete;
    this.mimeRegistry = contentLocation.getRepository().getMimeRegistry();
    if ( contentLocation instanceof FileContentLocation ) {
      final FileContentLocation fileContentLocation = (FileContentLocation) contentLocation;
      targetDirectory = (File) fileContentLocation.getContentId();
      if ( targetDirectory.isDirectory() == false ) {
        throw new NullPointerException();
      }
    }
    if ( targetDirectory == null ) {
      fallback = new DefaultNameGenerator( contentLocation );
    }
  }

  /**
   * Generates a new name for the location. The name-generator may use both the name-hint and mimetype to compute the
   * new name.
   * 
   * @param nameHint
   *          the name hint, usually a identifier for the new filename (can be null).
   * @param mimeType
   *          the mime type of the new filename. Usually used to compute a suitable file-suffix.
   * @return the generated name, never null.
   * @throws org.pentaho.reporting.libraries.repository.ContentIOException
   *           if the name could not be generated for any reason.
   */
  public String generateName( final String nameHint, final String mimeType ) throws ContentIOException {
    if ( fallback != null ) {
      return fallback.generateName( nameHint, mimeType );
    }
    final String suffix = mimeRegistry.getSuffix( mimeType );
    try {
      final IApplicationContext appCtx = PentahoSystem.getApplicationContext(); // Use the deleter functionality in the application context
      final IPentahoSession userSession = PentahoSessionHolder.getSession();
      final File tempFile = appCtx.createTempFile( userSession, nameHint, "." + suffix, safeToDelete );
      return tempFile.getName();
    } catch ( IOException e ) {
      throw new ContentIOException( "Unable to generate a name for the data file", e );
    }
  }
}

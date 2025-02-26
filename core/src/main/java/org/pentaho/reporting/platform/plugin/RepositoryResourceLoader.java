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


package org.pentaho.reporting.platform.plugin;

import java.net.URL;
import java.util.HashMap;
import java.util.Map;

import org.pentaho.reporting.libraries.resourceloader.ResourceData;
import org.pentaho.reporting.libraries.resourceloader.ResourceException;
import org.pentaho.reporting.libraries.resourceloader.ResourceKey;
import org.pentaho.reporting.libraries.resourceloader.ResourceKeyCreationException;
import org.pentaho.reporting.libraries.resourceloader.ResourceLoader;
import org.pentaho.reporting.libraries.resourceloader.ResourceLoadingException;
import org.pentaho.reporting.libraries.resourceloader.ResourceManager;
import org.pentaho.reporting.platform.plugin.messages.Messages;

/**
 * This class is implemented to support loading solution files from the pentaho repository into pentaho-reporting
 * 
 * @author Will Gorman/Michael D'Amour
 */
public class RepositoryResourceLoader implements ResourceLoader {

  public static final String SOLUTION_SCHEMA_NAME = "pentaho2"; //$NON-NLS-1$

  public static final String SCHEMA_SEPARATOR = "://"; //$NON-NLS-1$

  public static final String PATH_SEPARATOR = "/"; //$NON-NLS-1$

  public static final String WIN_PATH_SEPARATOR = "\\"; //$NON-NLS-1$

  /**
   * keep track of the resource manager
   */
  private ResourceManager manager;

  /**
   * default constructor
   */
  public RepositoryResourceLoader() {
  }

  /**
   * set the resource manager
   * 
   * @param manager
   *          resource manager
   */
  public void setResourceManager( final ResourceManager manager ) {
    this.manager = manager;
  }

  /**
   * get the resource manager
   * 
   * @return resource manager
   */
  public ResourceManager getManager() {
    return manager;
  }

  /**
   * get the schema name, in this case it's always "solution"
   * 
   * @return the schema name
   */
  public String getSchema() {
    return SOLUTION_SCHEMA_NAME;
  }

  /**
   * create a resource data object
   * 
   * @param key
   *          resource key
   * @return resource data
   * @throws ResourceLoadingException
   */
  public ResourceData load( final ResourceKey key ) throws ResourceLoadingException {
    return new RepositoryResourceData( key );
  }

  /**
   * Checks, whether this resource loader implementation was responsible for creating this key.
   * 
   * @param key
   *          the key that should be tested.
   * @return true, if the key is supported.
   */
  public boolean isSupportedKey( final ResourceKey key ) {
    if ( key.getSchema().equals( getSchema() ) ) {
      return true;
    }
    return false;
  }

  /**
   * Creates a new resource key from the given object and the factory keys.
   * 
   * @param value
   *          the key value.
   * @param factoryKeys
   *          optional parameter map (can be null).
   * @return the created key or null, if the format was not recognized.
   * @throws ResourceKeyCreationException
   *           if creating the key failed.
   */
  public ResourceKey createKey( final Object value, final Map factoryKeys ) throws ResourceKeyCreationException {
    if ( value instanceof String ) {
      final String valueString = (String) value;
      if ( valueString.startsWith( getSchema() + SCHEMA_SEPARATOR ) ) {
        final String path = valueString.substring( getSchema().length() + SCHEMA_SEPARATOR.length() );
        return new ResourceKey( getSchema(), path, factoryKeys );
      }
    }
    return null;
  }

  /**
   * derive a key from an existing key, used when a relative path is given.
   * 
   * @param parent
   *          the parent key
   * @param data
   *          the new data to be keyed
   * @return derived key
   * @throws ResourceKeyCreationException
   */
  public ResourceKey deriveKey( final ResourceKey parent, String path, final Map data )
    throws ResourceKeyCreationException {

    // update url to absolute path if currently a relative path
    if ( !path.startsWith( getSchema() + SCHEMA_SEPARATOR ) ) {
      // we are looking for the current directory specified by the parent. currently
      // the pentaho system uses the native File.separator, so we need to support it
      // we're simply looking for the last "/" or "\" in the parent's url.
      final int winindex = ( (String) parent.getIdentifier() ).lastIndexOf( WIN_PATH_SEPARATOR );
      final int regindex = ( (String) parent.getIdentifier() ).lastIndexOf( PATH_SEPARATOR );
      int dirindex = 0;
      if ( winindex > regindex ) {
        dirindex = winindex + WIN_PATH_SEPARATOR.length();
      } else {
        dirindex = regindex + PATH_SEPARATOR.length();
      }
      path = ( (String) parent.getIdentifier() ).substring( 0, dirindex ) + path;
    }
    final Map derivedValues = new HashMap( parent.getFactoryParameters() );
    if ( data != null ) {
      derivedValues.putAll( data );
    }
    return new ResourceKey( getSchema(), path, derivedValues );
  }

  public ResourceKey deserialize( final ResourceKey bundleKey, final String stringKey )
    throws ResourceKeyCreationException {
    // For now, we are just going to have to pass on this one
    throw new ResourceKeyCreationException( Messages.getInstance().getString(
        "ReportPlugin.cannotDeserializeZipResourceKey" ) ); //$NON-NLS-1$
  }

  public String serialize( final ResourceKey bundleKey, final ResourceKey key ) throws ResourceException {
    // For now, we are just going to have to pass on this one
    throw new ResourceKeyCreationException( Messages.getInstance().getString(
        "ReportPlugin.cannotSerializeZipResourceKey" ) ); //$NON-NLS-1$
  }

  public URL toURL( final ResourceKey key ) {
    // not supported ..
    return null;
  }

  public boolean isSupportedDeserializer( final String data ) {
    // For now, we are just going to have to pass on this one
    return false;
  }

}

/*
 * Copyright (C) 2012 Red Hat, Inc. (jcasey@redhat.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.commonjava.maven.ext.core.util;

import org.apache.commons.lang.StringUtils;
import org.apache.maven.model.Profile;
import org.commonjava.maven.ext.common.ManipulationException;
import org.commonjava.maven.ext.common.model.Project;
import org.commonjava.maven.ext.common.util.ProfileUtils;
import org.commonjava.maven.ext.common.util.PropertyResolver;
import org.commonjava.maven.ext.core.ManipulationSession;
import org.commonjava.maven.ext.core.impl.Version;
import org.commonjava.maven.ext.core.state.CommonState;
import org.commonjava.maven.ext.core.state.VersioningState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;

import static org.apache.commons.lang.StringUtils.isNotEmpty;

/**
 * Commonly used manipulations / extractions from project / user (CLI) properties.
 */
public final class PropertiesUtils
{
    private final static Logger logger = LoggerFactory.getLogger( PropertiesUtils.class );

    private PropertiesUtils()
    {
    }

    /**
     * Filter Properties by accepting only properties with names that start with prefix. Trims the prefix
     * from the property names when inserting them into the returned Map.
     * @param properties the properties to filter.
     * @param prefix The String that must be at the start of the property names
     * @return map of properties with matching prepend and their values
     */
    public static Map<String, String> getPropertiesByPrefix( final Properties properties, final String prefix )
    {
        final Map<String, String> matchedProperties = new HashMap<>();
        final int prefixLength = prefix.length();

        for ( final String propertyName : properties.stringPropertyNames() )
        {
            if ( propertyName.startsWith( prefix ) )
            {
                final String trimmedPropertyName = propertyName.substring( prefixLength );
                String value = properties.getProperty( propertyName );
                if ( value != null && value.equals( "true" ) )
                {
                    logger.warn( "Work around Brew/Maven bug - removing erroneous 'true' value for {}.",
                                 trimmedPropertyName );
                    value = "";
                }
                matchedProperties.put( trimmedPropertyName, value );
            }
        }

        return matchedProperties;
    }

    /**
     * Recursively update properties.
     *
     * @param session the DependencyState
     * @param project the current set of projects we are scanning.
     * @param ignoreStrict whether to ignore strict alignment.
     * @param key a key to look for.
     * @param newValue a value to look for.
     * @return {@code PropertyUpdate} enumeration showing status of any changes.
     * @throws ManipulationException if an error occurs
     */
    public static PropertyUpdate updateProperties( ManipulationSession session, Project project, boolean ignoreStrict,
                                                   String key, String newValue ) throws ManipulationException
    {
        final String resolvedValue = PropertyResolver.resolveProperties( session, project.getInheritedList(), "${" + key + '}' );

        logger.debug( "Fully resolvedValue is {} for {} ", resolvedValue, key );

        if ( "project.version".equals( key ) )
        {
            logger.debug ("Not updating key {} with {} ", key, newValue );
            return PropertyUpdate.IGNORE;
        }

        for ( final Project p : project.getReverseInheritedList() )
        {
            if ( p.getModel().getProperties().containsKey( key ) )
            {
                logger.trace( "Searching properties of {} ", p );
                return internalUpdateProperty( session, p, ignoreStrict, key, newValue, resolvedValue, p.getModel().getProperties() );
            }
            else
            {
                for ( Profile pr : ProfileUtils.getProfiles( session, p.getModel() ) )
                {
                    logger.trace( "Searching properties of profile {} within project {} ", pr.getId(), p );
                    // Lets check the profiles for property updates...
                    if ( pr.getProperties().containsKey( key ) )
                    {
                        return internalUpdateProperty( session, p, ignoreStrict, key, newValue, resolvedValue, pr.getProperties() );
                    }
                }
            }
        }

        return PropertyUpdate.NOTFOUND;
    }


    private static PropertyUpdate internalUpdateProperty( ManipulationSession session, Project p, boolean ignoreStrict,
                                                          String key, String newValue, String resolvedValue,
                                                          Properties props )
                    throws ManipulationException
    {
        final CommonState state = session.getState( CommonState.class );
        final String oldValue = props.getProperty( key );

        logger.info( "Updating property {} / {} with {} ", key, oldValue, newValue );

        PropertyUpdate found = PropertyUpdate.FOUND;

        // We'll only recursively resolve the property if its a single >${foo}<. If its one of
        // >${foo}value${foo}<
        // >${foo}${foo}<
        // >value${foo}<
        // >${foo}value<
        // it becomes hairy to verify strict compliance and to correctly split the old value and
        // update it with a portion of the new value.
        if ( oldValue != null && oldValue.startsWith( "${" ) && oldValue.endsWith( "}" ) &&
                        !( StringUtils.countMatches( oldValue, "${" ) > 1 ) )
        {
            logger.debug( "Recursively resolving {} ", oldValue.substring( 2, oldValue.length() - 1 ) );

            if ( updateProperties( session, p, ignoreStrict,
                                   oldValue.substring( 2, oldValue.length() - 1 ), newValue ) == PropertyUpdate.NOTFOUND )
            {
                logger.error( "Recursive property not found for {} with {} ", oldValue, newValue );
                return PropertyUpdate.NOTFOUND;
            }
        }
        else
        {
            if ( state.getStrict() && !ignoreStrict )
            {
                if ( !checkStrictValue( session, resolvedValue, newValue ) )
                {
                    if ( state.getFailOnStrictViolation() )
                    {
                        throw new ManipulationException(
                                        "Replacing original property version {} (fully resolved: {} ) with new version {} for {} violates the strict version-alignment rule!",
                                        oldValue, resolvedValue, newValue, key );
                    }
                    else
                    {
                        logger.warn( "Replacing original property version {} with new version {} for {} violates the strict version-alignment rule!",
                                     oldValue, newValue, key );
                        // Ignore the dependency override. As found has been set to true it won't inject
                        // a new property either.
                        return found;
                    }
                }
            }

            // TODO: Does not handle explicit overrides.
            if ( oldValue != null && oldValue.contains( "${" ) &&
                            !( oldValue.startsWith( "${" ) && oldValue.endsWith( "}" ) ) || (
                            StringUtils.countMatches( oldValue, "${" ) > 1 ) )
            {
                // This block handles
                // >${foo}value${foo}<
                // >${foo}${foo}<
                // >value${foo}<
                // >${foo}value<
                // We don't attempt to recursively resolve those as tracking the split of the variables, combined
                // with the update and strict version checking becomes overly fragile.

                if ( ignoreStrict )
                {
                    throw new ManipulationException(
                                    "NYI : handling for versions with explicit overrides (" + oldValue + ") with multiple embedded properties is NYI. " );
                }
                if ( resolvedValue.equals( newValue ))
                {
                    logger.warn( "Nothing to update as original key {} value matches new value {} ", key,
                                 newValue );
                    found = PropertyUpdate.IGNORE;
                }
                newValue = oldValue + StringUtils.removeStart( newValue, resolvedValue );
                logger.info( "Ignoring new value due to embedded property {} and appending {} ", oldValue,
                             newValue );
            }

            props.setProperty( key, newValue );
        }
        return found;
    }

    /**
     * Retrieve any configured rebuild suffix.
     * @param session Current ManipulationSession
     * @return string suffix.
     */
    public static String getSuffix (ManipulationSession session)
    {
        final VersioningState versioningState = session.getState( VersioningState.class );
        String suffix = null;

        if ( versioningState.getIncrementalSerialSuffix() != null && !versioningState.getIncrementalSerialSuffix().isEmpty() )
        {
            suffix = versioningState.getIncrementalSerialSuffix();
        }
        else if ( versioningState.getSuffix() != null && !versioningState.getSuffix().isEmpty() )
        {
            suffix = versioningState.getSuffix().substring( 0, versioningState.getSuffix().lastIndexOf( '-' ) );
        }
        return suffix;
    }

    /**
     * Check the version change is valid in strict mode.
     *
     * @param session the manipulation session
     * @param oldValue the original version
     * @param newValue the new version
     * @return true if the version can be changed to the new version
     */
    public static boolean checkStrictValue( ManipulationSession session, String oldValue, String newValue )
    {
        if ( oldValue == null || newValue == null )
        {
            return false;
        }
        else if ( oldValue.equals( newValue ) )
        {
            // The old version and new version matches. So technically it can be changed (even if its a bit pointless).
            return true;
        }

        final CommonState cState = session.getState( CommonState.class );
        final VersioningState vState = session.getState( VersioningState.class );
        final boolean ignoreSuffix = cState.getStrictIgnoreSuffix();

        // New value might be e.g. 3.1-rebuild-1 or 3.1.0.rebuild-1 (i.e. it *might* be OSGi compliant).
        String newVersion = newValue;
        String suffix = getSuffix( session );

        String v = oldValue ;
        if ( !vState.preserveSnapshot() )
        {
            v = Version.removeSnapshot( v );
        }

        String osgiVersion = Version.getOsgiVersion( v );

        if ( isNotEmpty ( suffix ))
        {
            // If we have been configured to ignore the suffix (e.g. rebuild-n) then, assuming that
            // the oldValue actually contains the suffix process it.
            if ( ignoreSuffix && oldValue.contains( suffix ) )
            {
                HashSet<String> s = new HashSet<>();
                s.add( oldValue );
                s.add( newValue );

                String x = String.valueOf( Version.findHighestMatchingBuildNumber( v, s ) );

                // If the new value has the higher matching build number strip the old suffix to allow for strict
                // matching.
                if ( newValue.endsWith( x ) )
                {
                    String oldValueCache = oldValue;
                    oldValue = oldValue.substring( 0, oldValue.indexOf( suffix ) - 1 );
                    v = oldValue;
                    osgiVersion = Version.getOsgiVersion( v );
                    logger.debug( "Updating version to {} and for oldValue {} with newValue {} ", v, oldValueCache,
                                  newValue );

                }
                else
                {
                    logger.warn( "strictIgnoreSuffix set but unable to align from {} to {}", oldValue, newValue );
                }
            }

            // We only need to dummy up and add a suffix if there is no qualifier. This allows us
            // to work out the OSGi version.
            if ( !Version.hasQualifier( v ) )
            {
                v = Version.appendQualifierSuffix( v, suffix );
                osgiVersion = Version.getOsgiVersion( v );
                osgiVersion = osgiVersion.substring( 0, osgiVersion.indexOf( suffix ) - 1 );
            }
            if ( newValue.contains( suffix ) )
            {
                newVersion = newValue.substring( 0, newValue.indexOf( suffix ) - 1 );
            }
        }
        logger.debug( "Comparing original version {} and OSGi variant {} with new version {} and suffix removed {} ",
                      oldValue, osgiVersion, newValue, newVersion );

        // We compare both an OSGi'ied oldVersion and the non-OSGi version against the possible new version (which has
        // had its suffix stripped) in order to check whether its a valid change.
        boolean result = false;
        if ( oldValue.equals( newVersion ) || osgiVersion.equals( newVersion ) )
        {
            result = true;
        }
        return result;
    }

    /**
     * This will check if the old version (e.g. in a plugin or dependency) is a property and if so
     * store the mapping in a map.
     *
     *
     * @param project the current project the needs to cache the value.
     * @param state CommonState to retrieve property clash value QoS.
     * @param versionPropertyUpdateMap the map to store any updates in
     * @param oldVersion original property value
     * @param newVersion new property value
     * @param originalType that this property is used in (i.e. a plugin or a dependency)
     * @param force Whether to check for an existing property or force the insertion
     * @return true if a property was found and cached.
     * @throws ManipulationException if an error occurs.
     */
    public static boolean cacheProperty( Project project, CommonState state, Map<Project, Map<String, String>> versionPropertyUpdateMap, String oldVersion,
                                         String newVersion, Object originalType, boolean force )
                    throws ManipulationException
    {
        boolean result = false;
        Map<String,String> projectProps = versionPropertyUpdateMap.get( project );
        if ( projectProps == null )
        {
            versionPropertyUpdateMap.put ( project, ( projectProps = new HashMap<>( ) ) );
        }

        if ( oldVersion != null && oldVersion.contains( "${" ) )
        {
            final int endIndex = oldVersion.indexOf( '}' );
            final String oldProperty = oldVersion.substring( 2, endIndex );

            // We don't attempt to cache any value that contains more than one property or contains a property
            // combined with a hardcoded value.
            if ( oldVersion.contains( "${" ) && !( oldVersion.startsWith( "${" ) && oldVersion.endsWith( "}" ) ) || (
                            StringUtils.countMatches( oldVersion, "${" ) > 1 ) )
            {
                logger.debug( "For {} ; original version contains hardcoded value or multiple embedded properties. Not caching value ( {} -> {} )",
                              originalType, oldVersion, newVersion );
            }
            else if ( "project.version".equals( oldProperty ) )
            {
                logger.debug( "For {} ; original version was a property mapping. Not caching value as property is built-in ( {} -> {} )",
                              originalType, oldProperty, newVersion );
            }
            else
            {
                logger.debug( "For {} ; original version was a property mapping; caching new value for update {} -> {} for project {} ",
                              originalType, oldProperty, newVersion, project );

                final String oldVersionProp = oldVersion.substring( 2, oldVersion.length() - 1 );

                // We check if we are replacing a property and there is already a mapping. While we don't allow
                // a property to be updated to two different versions, if a dependencyExclusion (i.e. a force override)
                // has been specified this will bypass the check.
                String existingPropertyMapping = projectProps.get( oldVersionProp );

                if ( existingPropertyMapping != null && !existingPropertyMapping.equals( newVersion ) )
                {
                    if ( force )
                    {
                        logger.debug( "Override property replacement of {} with force version override {}",
                                      existingPropertyMapping, newVersion );
                    }
                    else
                    {
                        if ( state.getPropertyClashFails() )
                        {
                            logger.error( "Replacing property '{}' with a new version but the existing version does not match. Old value is {} and new is {}",
                                          oldVersionProp, existingPropertyMapping, newVersion );
                            throw new ManipulationException(
                                            "Property replacement clash - updating property '{}' to both {} and {} ",
                                            oldVersionProp, existingPropertyMapping, newVersion );
                        }
                        else
                        {
                            logger.warn ("Replacing property '{}' with a new version would clash with existing version which does not match. Old value is {} and new is {}. Purging update of existing property.",
                                          oldVersionProp, existingPropertyMapping, newVersion );
                            projectProps.remove( oldVersionProp );
                            return false;
                        }
                    }
                }
                projectProps.put( oldVersionProp, newVersion );
                result = true;
            }
        }
        return result;
    }

    public static String handleDeprecatedProperty (Properties userProps, PropertyFlag flag)
    {
        return handleDeprecatedProperty( userProps, flag, null );
    }

    public static String handleDeprecatedProperty (Properties userProps, PropertyFlag flag, String defaultValue )
    {
        String result;
        if ( userProps.containsKey( flag.getDeprecated() ) )
        {
            logger.error ("Deprecated property usage {} ", flag.getDeprecated());
            logger.warn ("Property {} is deprecated. Please use property {} instead.", flag.getDeprecated(), flag.getCurrent() );

            result = userProps.getProperty( flag.getDeprecated(), defaultValue );
        }
        else
        {
            result = userProps.getProperty( flag.getCurrent(), defaultValue );
        }
        return result;
    }

    /**
     * Used to determine whether any property updates were successful of not. In the case of detecting that no properties are
     * needed IGNORE is returned. Effectively this is a slightly more explicit tri-state.
     */
    public enum PropertyUpdate
    {
        FOUND,
        NOTFOUND,
        IGNORE
    }
}

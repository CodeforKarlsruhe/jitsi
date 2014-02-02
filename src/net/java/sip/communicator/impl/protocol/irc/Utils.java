/*
 * Jitsi, the OpenSource Java VoIP and Instant Messaging client.
 * 
 * Distributable under LGPL license. See terms of license at gnu.org.
 */
package net.java.sip.communicator.impl.protocol.irc;

/**
 * Some IRC-related utility methods.
 * 
 * @author Danny van Heumen
 */
public final class Utils
{
    /**
     * Starting value of HTML entities.
     * 
     * The first 32 chars cannot be converted into HTML entities.
     */
    private static final int START_OF_HTML_ENTITIES = 32;

    /**
     * Private constructor since we do not need to construct anything.
     */
    private Utils()
    {
    }

    /**
     * Parse IRC text message and process possible control codes.
     * 
     * 1. Remove chars which have a value < 32, since there are no equivalent
     * HTML entities available when storing them in the history log.
     * 
     * @param text message
     * @return returns the processed message
     */
    public static String parse(String text)
    {
        StringBuilder builder = new StringBuilder(text);

        // TODO support IRC control codes for formatting (now only removes them)

        for (int i = 0; i < builder.length(); )
        {
            if (builder.charAt(i) < START_OF_HTML_ENTITIES)
            {
                builder.deleteCharAt(i);
            }
            else
            {
                // nothing to do here, go to next char
                i++;
            }
        }
        return builder.toString();
    }
}

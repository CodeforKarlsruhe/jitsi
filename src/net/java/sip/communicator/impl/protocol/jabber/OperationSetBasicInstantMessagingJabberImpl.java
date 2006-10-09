/*
 * SIP Communicator, the OpenSource Java VoIP and Instant Messaging client.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package net.java.sip.communicator.impl.protocol.jabber;

import java.util.*;

import org.jivesoftware.smack.*;
import org.jivesoftware.smack.filter.*;
import org.jivesoftware.smack.packet.*;
import org.jivesoftware.smack.provider.*;
import org.jivesoftware.smack.util.*;
import org.jivesoftware.smackx.*;
import net.java.sip.communicator.impl.protocol.jabber.extensions.*;
import net.java.sip.communicator.service.protocol.*;
import net.java.sip.communicator.service.protocol.Message;
import net.java.sip.communicator.service.protocol.event.*;
import net.java.sip.communicator.util.*;
import net.java.sip.communicator.service.protocol.jabberconstants.*;

/**
 * A straightforward implementation of the basic instant messaging operation
 * set.
 *
 * @author Damian Minkov
 */
public class OperationSetBasicInstantMessagingJabberImpl
    implements OperationSetBasicInstantMessaging
{
    private static final Logger logger =
        Logger.getLogger(OperationSetBasicInstantMessagingJabberImpl.class);

    /**
     * KeepAlive interval for sending packets
     */
    private static final long KEEPALIVE_INTERVAL = 180000l; // 3 minutes

    /**
     * The interval after which a packet is considered to be lost
     */
    private static final long KEEPALIVE_WAIT = 20000l;

    /**
     * The task sending packets
     */
    private KeepAliveSendTask keepAliveSendTask = null;
    /**
     * The timer executing tasks on specified intervals
     */
    private Timer keepAliveTimer = new Timer();
    /**
     * The queue holding the received packets
     */
    private LinkedList receivedKeepAlivePackets = new LinkedList();

    /**
     * A list of listeneres registered for message events.
     */
    private Vector messageListeners = new Vector();

    /**
     * The provider that created us.
     */
    private ProtocolProviderServiceJabberImpl jabberProvider = null;

    /**
     * A reference to the persistent presence operation set that we use
     * to match incoming messages to <tt>Contact</tt>s and vice versa.
     */
    private OperationSetPersistentPresenceJabberImpl opSetPersPresence = null;

    /**
     * Creates an instance of this operation set.
     * @param provider a ref to the <tt>ProtocolProviderServiceImpl</tt>
     * that created us and that we'll use for retrieving the underlying aim
     * connection.
     */
    OperationSetBasicInstantMessagingJabberImpl(
        ProtocolProviderServiceJabberImpl provider)
    {
        this.jabberProvider = provider;
        provider.addRegistrationStateChangeListener(new RegistrationStateListener());

        // register the KeepAlive Extension in the smack library
        ProviderManager.addExtensionProvider(KeepAliveEventProvider.ELEMENT_NAME,
                                             KeepAliveEventProvider.NAMESPACE,
                                             new KeepAliveEventProvider());
    }

    /**
     * Registeres a MessageListener with this operation set so that it gets
     * notifications of successful message delivery, failure or reception of
     * incoming messages..
     *
     * @param listener the <tt>MessageListener</tt> to register.
     */
    public void addMessageListener(MessageListener listener)
    {
        synchronized(messageListeners)
        {
            if(!messageListeners.contains(listener))
            {
                this.messageListeners.add(listener);
            }
        }
    }

    /**
     * Unregisteres <tt>listener</tt> so that it won't receive any further
     * notifications upon successful message delivery, failure or reception of
     * incoming messages..
     *
     * @param listener the <tt>MessageListener</tt> to unregister.
     */
    public void removeMessageListener(MessageListener listener)
    {
        synchronized(messageListeners)
        {
            this.messageListeners.remove(listener);
        }
    }

    /**
     * Create a Message instance for sending arbitrary MIME-encoding content.
     *
     * @param content content value
     * @param contentType the MIME-type for <tt>content</tt>
     * @param contentEncoding encoding used for <tt>content</tt>
     * @param subject a <tt>String</tt> subject or <tt>null</tt> for now subject.
     * @return the newly created message.
     */
    public Message createMessage(byte[] content, String contentType,
                                 String contentEncoding, String subject)
    {
        return new MessageJabberImpl(new String(content), contentType
                                  , contentEncoding, subject);
    }

    /**
     * Create a Message instance for sending a simple text messages with
     * default (text/plain) content type and encoding.
     *
     * @param messageText the string content of the message.
     * @return Message the newly created message
     */
    public Message createMessage(String messageText)
    {
        return new MessageJabberImpl(messageText, DEFAULT_MIME_TYPE
                                  , DEFAULT_MIME_ENCODING, null);
    }

    /**
     * Sends the <tt>message</tt> to the destination indicated by the
     * <tt>to</tt> contact.
     *
     * @param to the <tt>Contact</tt> to send <tt>message</tt> to
     * @param message the <tt>Message</tt> to send.
     * @throws java.lang.IllegalStateException if the underlying stack is
     * not registered and initialized.
     * @throws java.lang.IllegalArgumentException if <tt>to</tt> is not an
     * instance of ContactImpl.
     */
    public void sendInstantMessage(Contact to, Message message)
        throws IllegalStateException, IllegalArgumentException
    {
        try
        {
            assertConnected();

            Chat chat =
            jabberProvider.getConnection().
                createChat(to.getAddress());

            org.jivesoftware.smack.packet.Message msg = chat.createMessage();
            msg.setBody(message.getContent());

            MessageEventManager.
                addNotificationsRequests(msg, true, false, false, true);

            chat.sendMessage(msg);

            MessageDeliveredEvent msgDeliveredEvt
                = new MessageDeliveredEvent(
                    message, to, new Date());

            fireMessageEvent(msgDeliveredEvt);
        }
        catch (XMPPException ex)
        {
            logger.error("message not send", ex);
        }
    }

    /**
     * Utility method throwing an exception if the stack is not properly
     * initialized.
     * @throws java.lang.IllegalStateException if the underlying stack is
     * not registered and initialized.
     */
    private void assertConnected() throws IllegalStateException
    {
        if (jabberProvider == null)
            throw new IllegalStateException(
                "The provider must be non-null and signed on the "
                +"service before being able to communicate.");
        if (!jabberProvider.isRegistered())
            throw new IllegalStateException(
                "The provider must be signed on the service before "
                +"being able to communicate.");
    }

    /**
     * Our listener that will tell us when we're registered to
     */
    private class RegistrationStateListener
        implements RegistrationStateChangeListener
    {
        /**
         * The method is called by a ProtocolProvider implementation whenver
         * a change in the registration state of the corresponding provider had
         * occurred.
         * @param evt ProviderStatusChangeEvent the event describing the status
         * change.
         */
        public void registrationStateChanged(RegistrationStateChangeEvent evt)
        {
            logger.debug("The provider changed state from: "
                         + evt.getOldState()
                         + " to: " + evt.getNewState());

            if (evt.getNewState() == RegistrationState.REGISTERED)
            {
                opSetPersPresence = (OperationSetPersistentPresenceJabberImpl)
                    jabberProvider.getSupportedOperationSets()
                        .get(OperationSetPersistentPresence.class.getName());

                jabberProvider.getConnection().addPacketListener(
                        new SmackMessageListener(),
                        new PacketTypeFilter(
                            org.jivesoftware.smack.packet.Message.class));

                // run keepalive thread
                if(keepAliveSendTask == null)
                {
                    keepAliveSendTask = new KeepAliveSendTask();

                    keepAliveTimer.scheduleAtFixedRate(
                        keepAliveSendTask, KEEPALIVE_INTERVAL, KEEPALIVE_INTERVAL);
                }
            }
        }
    }

    /**
     * Delivers the specified event to all registered message listeners.
     * @param evt the <tt>EventObject</tt> that we'd like delivered to all
     * registered message listerners.
     */
    private void fireMessageEvent(EventObject evt)
    {
        Iterator listeners = null;
        synchronized (messageListeners)
        {
            listeners = new ArrayList(messageListeners).iterator();
        }

        while (listeners.hasNext())
        {
            MessageListener listener
                = (MessageListener) listeners.next();

            if (evt instanceof MessageDeliveredEvent)
            {
                listener.messageDelivered( (MessageDeliveredEvent) evt);
            }
            else if (evt instanceof MessageReceivedEvent)
            {
                listener.messageReceived( (MessageReceivedEvent) evt);
            }
            else if (evt instanceof MessageDeliveryFailedEvent)
            {
                listener.messageDeliveryFailed(
                    (MessageDeliveryFailedEvent) evt);
            }
        }
    }
    private class SmackMessageListener
        implements PacketListener
    {
        public void processPacket(Packet packet)
        {
            if(!(packet instanceof org.jivesoftware.smack.packet.Message))
                return;

            org.jivesoftware.smack.packet.Message msg =
                (org.jivesoftware.smack.packet.Message)packet;

            if(msg.getBody() == null)
                return;

            String fromUserID = StringUtils.parseBareAddress(msg.getFrom());

            if(logger.isDebugEnabled())
            {
                logger.debug("Received from "
                             + fromUserID
                             + " the message "
                             + msg.getBody());
            }

            KeepAliveEvent keepAliveEvent =
                (KeepAliveEvent)packet.getExtension(
                    KeepAliveEventProvider.ELEMENT_NAME,
                    KeepAliveEventProvider.NAMESPACE);
            if(keepAliveEvent != null)
            {
                keepAliveEvent.setFromUserID(fromUserID);
                receivedKeepAlivePackets.addLast(keepAliveEvent);
                return;
            }

            Message newMessage = createMessage(msg.getBody());

            Contact sourceContact =
                opSetPersPresence.findContactByID(fromUserID);

            if(msg.getType() == org.jivesoftware.smack.packet.Message.Type.ERROR)
            {
                logger.info("Message error received from " + fromUserID);

                int errorCode = packet.getError().getCode();
                int errorResultCode = MessageDeliveryFailedEvent.UNKNOWN_ERROR;

                if(errorCode == 503)
                {
                    org.jivesoftware.smackx.packet.MessageEvent msgEvent =
                        (org.jivesoftware.smackx.packet.MessageEvent)
                            packet.getExtension("x", "jabber:x:event");
                    if(msgEvent != null && msgEvent.isOffline())
                    {
                        errorResultCode =
                            MessageDeliveryFailedEvent.OFFLINE_MESSAGES_NOT_SUPPORTED;
                    }
                }

                MessageDeliveryFailedEvent ev =
                    new MessageDeliveryFailedEvent(newMessage,
                                                   sourceContact,
                                                   errorResultCode,
                                                   new Date());
                fireMessageEvent(ev);
                return;
            }

            if(sourceContact == null)
            {
                logger.debug("received a message from an unknown contact: "
                                   + fromUserID);
                //create the volatile contact
                sourceContact = opSetPersPresence
                    .createVolatileContact(fromUserID);
            }

            MessageReceivedEvent msgReceivedEvt
                = new MessageReceivedEvent(
                    newMessage, sourceContact , new Date() );

            fireMessageEvent(msgReceivedEvt);
        }
    }

    /**
     * Task sending packets on intervals.
     * The task is runned on specified intervals by the keepAliveTimer
     */
    private class KeepAliveSendTask
        extends TimerTask
    {
        public void run()
        {
            try
            {
                // if we are not registerd do nothing
                if(!jabberProvider.isRegistered())
                {
                    logger.trace("provider not registered. "
                                 +"won't send keep alive. acc.id="
                                 + jabberProvider.getAccountID()
                                    .getAccountUniqueID());
                    return;
                }

                Chat chat =
                    jabberProvider.getConnection().
                    createChat(jabberProvider.getAccountID().getUserID());

                org.jivesoftware.smack.packet.Message msg = chat.createMessage();

                //make the system message unique (emcho: I think some servers "
                //may be ignoring repetitive messages.)
                msg.setBody("SYSTEM MESSAGE! ("
                            + System.currentTimeMillis()
                            + ")");

                KeepAliveEvent keepAliveEvent = new KeepAliveEvent();

                keepAliveEvent.setSrcOpSetHash(
                    OperationSetBasicInstantMessagingJabberImpl.this.hashCode());
                keepAliveEvent.setSrcProviderHash(jabberProvider.hashCode());

                // add keepalive data
                msg.addExtension(keepAliveEvent);

                // schedule the check task
                keepAliveTimer.schedule(
                    new KeepAliveCheckTask(), KEEPALIVE_WAIT);

                logger.trace(
                    "send keepalive for acc: "
                    + jabberProvider.getAccountID().getAccountUniqueID());
                chat.sendMessage(msg);
            }
            catch (XMPPException ex)
            {
                logger.error(
                    "Error sending keep alive packet for account"
                    + jabberProvider.getAccountID().getAccountUniqueID()
                    , ex);
            }
        }
    }

    /**
     * Check if the first received packet in the queue
     * is ok and if its not or the queue has no received packets
     * the this means there is some network problem, so fire event
     */
    private class KeepAliveCheckTask
        extends TimerTask
    {
        public void run()
        {
            try
            {
                // check till we find a correct message
                // or if NoSuchElementException is thrown
                // there is no message
                while(!checkFirstPacket());
            }
            catch (NoSuchElementException ex)
            {
                logger.error(
                    "Did not receive last keep alive packet for account "
                    + jabberProvider.getAccountID().getAccountUniqueID());
                logger.error("unregistering.");
                fireUnregisterd();
            }
        }

        /**
         * Checks whether first packet in queue is ok
         * @return boolean
         * @throws NoSuchElementException
         */
        boolean checkFirstPacket()
            throws NoSuchElementException
        {
            KeepAliveEvent receivedEvent =
                    (KeepAliveEvent)receivedKeepAlivePackets.removeLast();

            if(jabberProvider.hashCode() != receivedEvent.getSrcProviderHash() ||
                    OperationSetBasicInstantMessagingJabberImpl.this.hashCode() !=
                    receivedEvent.getSrcOpSetHash() ||
                    !jabberProvider.getAccountID().getUserID().
                                        equals(receivedEvent.getFromUserID()) )
                return false;
            else
                return true;
        }

        /**
         * Fire Unregistered event
         */
        void fireUnregisterd()
        {
            jabberProvider.fireRegistrationStateChanged(
                jabberProvider.getRegistrationState(),
                RegistrationState.CONNECTION_FAILED,
                RegistrationStateChangeEvent.REASON_INTERNAL_ERROR, null);

            opSetPersPresence.fireProviderPresenceStatusChangeEvent(
                opSetPersPresence.getPresenceStatus(),
                JabberStatusEnum.OFFLINE);
        }
    }
}

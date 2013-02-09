package com.pusher.client.channel.impl;

import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.powermock.api.mockito.PowerMockito;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;

import com.pusher.client.AuthorizationFailureException;
import com.pusher.client.channel.ChannelEventListener;
import com.pusher.client.channel.ChannelState;
import com.pusher.client.channel.PrivateChannelEventListener;
import com.pusher.client.connection.ConnectionState;
import com.pusher.client.connection.ConnectionStateChange;
import com.pusher.client.connection.impl.InternalConnection;
import com.pusher.client.util.Factory;
import com.pusher.client.util.InstantExecutor;

@RunWith(PowerMockRunner.class)
@PrepareForTest({Factory.class})
public class ChannelManagerTest {

    private static final String CHANNEL_NAME = "my-channel";
    private static final String PRIVATE_CHANNEL_NAME = "private-my-channel";
    private static final String OUTGOING_SUBSCRIBE_MESSAGE = "{\"event\":\"pusher:subscribe\"}";
    private static final String OUTGOING_UNSUBSCRIBE_MESSAGE = "{\"event\":\"pusher:unsubscribe\"}";
    private static final String SOCKET_ID = "21234.41243";
    private static final String PRIVATE_OUTGOING_SUBSCRIBE_MESSAGE = "{\"event\":\"pusher:subscribe\", \"data\":{}}";
    
    private ChannelManager channelManager;
    private @Mock InternalConnection mockConnection;
    private @Mock InternalChannel mockInternalChannel;
    private @Mock ChannelEventListener mockEventListener;
    private @Mock PrivateChannelImpl mockPrivateChannel;
    private @Mock PrivateChannelEventListener mockPrivateChannelEventListener;
    
    @Before
    public void setUp() throws AuthorizationFailureException {
	
	PowerMockito.mockStatic(Factory.class);
	
	when(Factory.getEventQueue()).thenReturn(new InstantExecutor());
	when(mockInternalChannel.getName()).thenReturn(CHANNEL_NAME);
	when(mockInternalChannel.toSubscribeMessage()).thenReturn(OUTGOING_SUBSCRIBE_MESSAGE);
	when(mockInternalChannel.toUnsubscribeMessage()).thenReturn(OUTGOING_UNSUBSCRIBE_MESSAGE);
	when(mockInternalChannel.getEventListener()).thenReturn(mockEventListener);
	when(mockConnection.getSocketId()).thenReturn(SOCKET_ID);
	when(mockConnection.getState()).thenReturn(ConnectionState.CONNECTED);
	when(mockPrivateChannel.getName()).thenReturn(PRIVATE_CHANNEL_NAME);
	when(mockPrivateChannel.toSubscribeMessage()).thenReturn(PRIVATE_OUTGOING_SUBSCRIBE_MESSAGE);
	when(mockPrivateChannel.getEventListener()).thenReturn(mockPrivateChannelEventListener);
	
	this.channelManager = new ChannelManager(mockConnection);
    }
    
    @Test
    public void testRegistersAsAConnectionListenerWhenConstructed() {
	verify(mockConnection).bind(ConnectionState.CONNECTED, channelManager);
    }
    
    @Test(expected=IllegalArgumentException.class)
    public void testConstructWithNullConnectionThrowsException() {
	new ChannelManager(null);
    }
    
    @Test
    public void testSubscribeWithAListenerAndNoEventsSubscribes() {
	channelManager.subscribeTo(mockInternalChannel, mockEventListener);
	
	verify(mockConnection).sendMessage(OUTGOING_SUBSCRIBE_MESSAGE);
	verify(mockInternalChannel, never()).bind(anyString(), any(ChannelEventListener.class));
    }
    
    @Test
    public void testSubscribeWithAListenerAndEventsBindsTheListenerToTheEventsBeforeSubscribing() {
	channelManager.subscribeTo(mockInternalChannel, mockEventListener, "event1", "event2");
	
	verify(mockInternalChannel).bind("event1", mockEventListener);
	verify(mockInternalChannel).bind("event2", mockEventListener);
	verify(mockConnection).sendMessage(OUTGOING_SUBSCRIBE_MESSAGE);
    }
    
    @Test
    public void testSubscribeSetsStatusOfChannelToSubscribeSent() {
	channelManager.subscribeTo(mockInternalChannel, mockEventListener);
	verify(mockInternalChannel).updateState(ChannelState.SUBSCRIBE_SENT);
    }
    
    @Test
    public void testSubscribeWithANullListenerAndNoEventsSubscribes() {
	channelManager.subscribeTo(mockInternalChannel, null);
	
	verify(mockConnection).sendMessage(OUTGOING_SUBSCRIBE_MESSAGE);
	verify(mockInternalChannel, never()).bind(anyString(), any(ChannelEventListener.class));
    }
    
    @Test(expected=IllegalArgumentException.class)
    public void testSubscribeWithNullChannelThrowsException() {
	channelManager.subscribeTo(null, mockEventListener);
    }
    
    @Test(expected=IllegalArgumentException.class)
    public void testSubscribeWithADuplicateNameThrowsException() {
	channelManager.subscribeTo(mockInternalChannel, mockEventListener);
	channelManager.subscribeTo(mockInternalChannel, mockEventListener);
    }
    
    @Test
    public void testSubscribeToPrivateChannelSubscribes() throws AuthorizationFailureException {
	channelManager.subscribeTo(mockPrivateChannel, mockPrivateChannelEventListener);
	
	verify(mockPrivateChannel).toSubscribeMessage();
	verify(mockConnection).sendMessage(PRIVATE_OUTGOING_SUBSCRIBE_MESSAGE);
    }
    
    @Test
    public void testSubscribeWhileDisconnectedQueuesSubscriptionUntilConnectedCallbackIsReceived() {
	when(mockConnection.getState()).thenReturn(ConnectionState.DISCONNECTED);
	
	channelManager.subscribeTo(mockInternalChannel, mockEventListener);
	verify(mockConnection, never()).sendMessage(anyString());
	
	when(mockConnection.getState()).thenReturn(ConnectionState.CONNECTED);
	channelManager.onConnectionStateChange(new ConnectionStateChange(ConnectionState.CONNECTING, ConnectionState.CONNECTED));
	verify(mockConnection).sendMessage(OUTGOING_SUBSCRIBE_MESSAGE);
    }
    
    @Test
    public void testDelayedSubscriptionThatFailsToAuthorizeNotifiesListenerAndDoesNotAttemptToSubscribe() {
    	AuthorizationFailureException exception = new AuthorizationFailureException("Unable to contact auth server");
	when(mockConnection.getState()).thenReturn(ConnectionState.DISCONNECTED);
	when(mockPrivateChannel.toSubscribeMessage()).thenThrow(exception);
	
	channelManager.subscribeTo(mockPrivateChannel, mockPrivateChannelEventListener);
	verify(mockConnection, never()).sendMessage(anyString());
	
	when(mockConnection.getState()).thenReturn(ConnectionState.CONNECTED);
	channelManager.onConnectionStateChange(new ConnectionStateChange(ConnectionState.CONNECTING, ConnectionState.CONNECTED));
	verify(mockPrivateChannelEventListener).onAuthenticationFailure("Unable to contact auth server", exception);
	verify(mockConnection, never()).sendMessage(anyString());
    }
    
    @Test
    public void testDelayedSubscriptionsAreNotSentAgainIfASecondConnectedCallbackIsReceived() {
	when(mockConnection.getState()).thenReturn(ConnectionState.DISCONNECTED);
	
	channelManager.subscribeTo(mockInternalChannel, mockEventListener);
	verify(mockConnection, never()).sendMessage(anyString());
	
	when(mockConnection.getState()).thenReturn(ConnectionState.CONNECTED);
	channelManager.onConnectionStateChange(new ConnectionStateChange(ConnectionState.CONNECTING, ConnectionState.CONNECTED));
	
	when(mockConnection.getState()).thenReturn(ConnectionState.DISCONNECTED);
	channelManager.onConnectionStateChange(new ConnectionStateChange(ConnectionState.CONNECTED, ConnectionState.DISCONNECTED));
	
	when(mockConnection.getState()).thenReturn(ConnectionState.CONNECTED);
	channelManager.onConnectionStateChange(new ConnectionStateChange(ConnectionState.DISCONNECTED, ConnectionState.CONNECTED));
	
	verify(mockConnection, times(1)).sendMessage(OUTGOING_SUBSCRIBE_MESSAGE);
    }
    
    @Test
    public void testDelayedSubscriptionDoesNotUpdateChannelStateToSubscribeSentUntilConnectedCallbackIsReceived() {
	when(mockConnection.getState()).thenReturn(ConnectionState.DISCONNECTED);
	
	channelManager.subscribeTo(mockInternalChannel, mockEventListener);
	verify(mockInternalChannel, never()).updateState(any(ChannelState.class));
	
	when(mockConnection.getState()).thenReturn(ConnectionState.CONNECTED);
	channelManager.onConnectionStateChange(new ConnectionStateChange(ConnectionState.CONNECTING, ConnectionState.CONNECTED));
	verify(mockInternalChannel).updateState(ChannelState.SUBSCRIBE_SENT);
    }
    
    @Test
    public void testReceiveMessageForSubscribedChannelPassesItToChannel() {
	channelManager.subscribeTo(mockInternalChannel, mockEventListener, "my-event");
	channelManager.onMessage("my-event", "{\"event\":\"my-event\",\"data\":{\"fish\":\"chips\"},\"channel\":\"" + CHANNEL_NAME + "\"}");
	
	verify(mockInternalChannel).onMessage("my-event", "{\"event\":\"my-event\",\"data\":{\"fish\":\"chips\"},\"channel\":\"" + CHANNEL_NAME + "\"}");
    }
    
    @Test
    public void testReceiveMessageWithNoMatchingChannelIsIgnoredAndDoesNotThrowException() {
	channelManager.subscribeTo(mockInternalChannel, mockEventListener, "my-event");
	channelManager.onMessage("my-event", "{\"event\":\"my-event\",\"data\":{\"fish\":\"chips\"},\"channel\":\"" + "DIFFERENT_CHANNEL_NAME" + "\"}");
	
	verify(mockInternalChannel, never()).onMessage(anyString(), anyString());
    }
    
    @Test
    public void testReceiveMessageWithNoChannelIsIgnoredAndDoesNotThrowException() {
	channelManager.onMessage("connection_established", "{\"event\":\"connection_established\",\"data\":{\"socket_id\":\"21098.967780\"}}");
    }
    
    @Test
    public void testUnsubscribeFromSubscribedChannelUnsubscribes() {
	channelManager.subscribeTo(mockInternalChannel, mockEventListener);
	channelManager.unsubscribeFrom(CHANNEL_NAME);
	
	verify(mockConnection).sendMessage(OUTGOING_UNSUBSCRIBE_MESSAGE);
    }
    
    @Test
    public void testUnsubscribeFromSubscribedChannelSetsStatusOfChannelToUnsubscribed() {
	channelManager.subscribeTo(mockInternalChannel, mockEventListener);
	channelManager.unsubscribeFrom(CHANNEL_NAME);
	
	verify(mockInternalChannel).updateState(ChannelState.UNSUBSCRIBED);
    }
    
    @Test(expected=IllegalArgumentException.class)
    public void testUnsubscribeWithNullChannelNameThrowsException() {
	channelManager.unsubscribeFrom(null);
    }
    
    @Test(expected=IllegalArgumentException.class)
    public void testUnsubscribeWhenNotSubscribedThrowsException() {
	channelManager.unsubscribeFrom(CHANNEL_NAME);
    }
    
    @Test
    public void testReceiveMessageAfterUnsubscribeDoesNotPassItToChannel() {
	channelManager.subscribeTo(mockInternalChannel, mockEventListener, "my-event");
	channelManager.unsubscribeFrom(CHANNEL_NAME);
	channelManager.onMessage("my-event", "{\"event\":\"my-event\",\"data\":{\"fish\":\"chips\"},\"channel\":\"" + CHANNEL_NAME + "\"}");
	
	verify(mockInternalChannel, never()).onMessage(anyString(), anyString());	
    }
    
    @Test
    public void testReceiveMessageAfterClearDoesNotPassItToChannel() {
	channelManager.subscribeTo(mockInternalChannel, mockEventListener, "my-event");
	channelManager.clear();
	channelManager.onMessage("my-event", "{\"event\":\"my-event\",\"data\":{\"fish\":\"chips\"},\"channel\":\"" + CHANNEL_NAME + "\"}");
	
	verify(mockInternalChannel, never()).onMessage(anyString(), anyString());	
    }
    
    @Test
    public void testQueuedSubscriptionIsNotSentIfClearIsCalledBeforeConnectedCallbackIsReceived() {
	when(mockConnection.getState()).thenReturn(ConnectionState.DISCONNECTED);
	
	channelManager.subscribeTo(mockInternalChannel, mockEventListener);
	channelManager.clear();
	channelManager.onConnectionStateChange(new ConnectionStateChange(ConnectionState.CONNECTING, ConnectionState.CONNECTED));
	
	verify(mockConnection, never()).sendMessage(anyString());
    }
}
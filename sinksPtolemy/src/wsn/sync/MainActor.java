package lsi.wsn.sync;


import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import ptolemy.actor.IOPort;
import ptolemy.actor.NoRoomException;
import ptolemy.actor.NoTokenException;
import ptolemy.actor.TypedAtomicActor;
import ptolemy.actor.TypedIOPort;
import ptolemy.actor.gui.ExpressionShellEffigy;
import ptolemy.actor.util.Time;
import ptolemy.data.IntToken;
import ptolemy.data.RecordToken;
import ptolemy.data.Token;
import ptolemy.data.expr.Parameter;
import ptolemy.data.type.BaseType;
import ptolemy.domains.wireless.kernel.WirelessIOPort;
import ptolemy.kernel.CompositeEntity;
import ptolemy.kernel.util.IllegalActionException;
import ptolemy.kernel.util.NameDuplicationException;
import ptolemy.vergil.icon.EditorIcon;
import ptolemy.vergil.kernel.attributes.EllipseAttribute;

/**
 * Receives beacon frames from the channel we are currently listening to and tries to estimate the parameters for the 
 * sink that sent the beacon. It calls the SendActor to schedule sending a frame to the sink when it is sure that such 
 * sink will be in the reception phase.
 */
public class MainActor extends TypedAtomicActor{

		// Constats
		final static double MIN_T = 0.5;
		final static double MIN_SLEEP = 6.0; 
        // Ports
        protected TypedIOPort beaconFramesInput; 
      
        // Data structure and flags used to store and calculate parameters
        HashMap<Integer, Time> sink_t;
        Map<Integer, Integer> sink_n;
        Map<Integer, Time> tokenPreviously;
        Map<Integer, Integer> valueOfTokenPreviously;
        Map<Integer, Time> receivedOneAt;
        Map<Integer, Boolean> waitingForFirstBacon;
        HashMap<Time, Integer> scheduled_channel;
        Map<Integer, Boolean> completelyScheduled;
        boolean noTokensForMoreThan1500ms;
        
        // The SendActor and the ChannelActor are member of the MainActor. They are used respectively to change channel and send frames to sinks.
        ChannelActor channeler;
        SendActor sender;

		public MainActor(CompositeEntity container, String name) throws NameDuplicationException, IllegalActionException  {
                super(container, name);
                // Port setup and instantiation of channeller and firer
                beaconFramesInput = new TypedIOPort(this, "input", true, false);
                beaconFramesInput.setTypeEquals(BaseType.INT);
                channeler = new ChannelActor((CompositeEntity)this.getContainer(), "CHANNELER");
                sender = new SendActor((CompositeEntity)this.getContainer(), "FIRER", channeler);
        }
        
        public void initialize() throws IllegalActionException {
        		super.initialize();
        		// Ensures members and fields are in a fresh state when running more simulation in a row
        		sender.initialize();
        		channeler.initialize();
        		sink_t = new HashMap<Integer, Time>();
                sink_n = new HashMap<Integer, Integer>();
                tokenPreviously = new HashMap<Integer, Time>();
                valueOfTokenPreviously = new HashMap<Integer, Integer>();
                receivedOneAt = new HashMap<Integer, Time>();
                waitingForFirstBacon = new HashMap<Integer, Boolean>();
                scheduled_channel = new HashMap<Time, Integer>();
                completelyScheduled = new HashMap<Integer, Boolean>();
                noTokensForMoreThan1500ms = false;
                initialiseCompletelyScheduled();
         }
        
        // Completely scheduled is a sink such that we have scheduled all sending.
		private void initialiseCompletelyScheduled() {
			completelyScheduled.put(11, false);
			completelyScheduled.put(12, false);
			completelyScheduled.put(13, false);
			completelyScheduled.put(14, false);
			completelyScheduled.put(15, false);
		}
        
		/**
		 * Main method that basically runs a state machine to determine the parameters of the sinks.
		 */
		public void fire() throws IllegalActionException {
			//The chanel we have received a token from
			 int channel = channeler.getCurrentChannel();
			 // The integer value in the beacon frame
			 int currentTokenValue = getFromChannel();
			 // If the parameters have been estimated already we don't have any work to do
			 if(areParametersFoundFor(channel)) {
				 return;
			 }
			 
			 // When we see a beacon frame with an int of value 10 this must be the value of N 
			 setNIfReceived10(channel, currentTokenValue);
			 
			 // noTokensForMoreThan1500ms is a flag that is true when we have been sitting on the channel for 
			 // more than 1.5 seconds without receiving a beacon frame
			 noTokensForMoreThan1500ms = checkWhenLastTokenOnThisChannelAndCompareToWhenCurrentToken();
			 
			 // Update the time we last received a token on the current channel
			 channeler.setTimeSwitchedToChannel(getTime()); 
			 
			 
			 if(noTokensForMoreThan1500ms || waitingForFirstBacon.get(channel) != null) { 
				 calculateN(channel, currentTokenValue);
				 if(areParametersFoundFor(channel)) { // Got all parameters so we can schedule all the reception phases of the sink and listen to the next channel
					 scheduleAllSendsAndMoveOn(channel, currentTokenValue);
				 } else { // Taken when we do not have T
					 if(gotOneBefore()) { 
						 // Calculate T based on the time in the past when we received a token with value one
						 // This is executed at the first beacon of the SYNC phase
						 calculateTWithDivisionBy12(channel);
						 scheduleAllSendsAndMoveOn(channel, currentTokenValue);
					 } else { 
						 if(gotTokenBefore()) {
							 // This is executed when we receive two tokens from the same SYNC phase
							 calculateTWithSuccessiveTokens(channel, currentTokenValue);
							 scheduleAllSendsAndMoveOn(channel, currentTokenValue);
						 } else {
							 if(currentTokenValue != 1) { 
								 // Save this token as it will be used for calculations when the next token will arrive
								 tokenPreviously.put(channel, getTime());
								 valueOfTokenPreviously.put(channel, currentTokenValue);
							 } else {
								 // We received a one and we do not know T.
								 // We don't have a way of determining T except waiting through the sleeping phase of the sink and receive
								 // the first token of the next SYNC phase, since that can potentially take long we simply move on, h
								 channeler.goToNextChannel(completelyScheduled);
							 }
						 }
					 }
				 }
			 } else { // Executed when we have waited for a beacon for less than 1.5 seconds
				 if(gotTokenBefore()) {
					 calculateTWithSuccessiveTokens(channel, currentTokenValue);
					 if(sink_n.get(channel) != null) { 
						 // If N was known we can schedule the channel in toto as we have just calculated T
						 scheduleAllSendsAndMoveOn(channel, currentTokenValue);
					 } else { 
						 // On the other hand, if we do not know N we can still schedule for the upcoming reception phase
						 double t = sink_t.get(channel).getDoubleValue();
						 scheduleSendForUpcomingReceptionPhase(channel, currentTokenValue, t);
						 scheduleSwitchToThisChannelWhenItWakesUp(channel, currentTokenValue, t);
						 // Move to next channel
						 channeler.goToNextChannel(completelyScheduled);
					 }
				 } else { // This is executed when we did not receive a beacon earlier
					 if(currentTokenValue == 1) {
						 // If the beacon value is one we try to schedule coming back to this channel after the minimum sleeping time
						 // dictated by the protocol minus an offset to ensure we do not miss a beacon sent exactly at that time.
						 scheduleSwitchToThisChannelAtMinimumWakeUp(channel);
						 channeler.goToNextChannel(completelyScheduled);
					 } else {
						 tokenPreviously.put(channel, getTime());
						 valueOfTokenPreviously.put(channel, currentTokenValue);
					 }
					 
				 }
			 }
        }
		
		/**
		 * Schedule a switch to this channel just before the earliest time the channel could wake up given we received a token 
		 *  of value one. If the schedule succeeds we mark the current channel as "waiting for first beacon"
		 *  meaning that the next token we will receive, regardless of how long we have been waiting on the channel, will be the
		 *  first of the synch phase.
		 * @param channel
		 * @throws IllegalActionException
		 */
		private void scheduleSwitchToThisChannelAtMinimumWakeUp(int channel) throws IllegalActionException {
			if(isSchedulable(getTime().add(MIN_SLEEP - 0.0002))) {
				 receivedOneAt.put(channel, getTime());
				 scheduled_channel.put(getTime().add(MIN_SLEEP - 0.0002), channel);
			 	 channeler.scheduleSwitchChannel(getTime().add(MIN_SLEEP - 0.0002) , channel, true);
			 	 waitingForFirstBacon.put(channeler.getCurrentChannel(), true);
			 }
		}
		
		/**
		 *  Schedule a switch to this channel just before it will wake up from the sleep phase.
		 *  It checks if such schedule is possible and if so, it also mark the current channel as "waiting for first beacon"
		 *  meaning that the next token we will receive, regardless of how long we have been waiting on the channel, will be the
		 *  first of the synch phase.
		 * @param channel
		 * @param currentTokenValue
		 * @param t
		 * @throws IllegalActionException
		 */
		private void scheduleSwitchToThisChannelWhenItWakesUp(int channel, int currentTokenValue, double t)
				throws IllegalActionException {
			Time channelWakeUp = getTime().add(((currentTokenValue * t) + t + (10 * t)) - 0.0001);
			 if(isSchedulable(channelWakeUp)) {
				 scheduled_channel.put(channelWakeUp, channel);
				 channeler.scheduleSwitchChannel(channelWakeUp, channel, true);
				 waitingForFirstBacon.put(channeler.getCurrentChannel(), true);
			 }
		}
		
		/**
		 * Schedule 
		 * @param channel
		 * @param currentTokenValue
		 * @param t
		 * @throws IllegalActionException
		 */
		private void scheduleSendForUpcomingReceptionPhase(int channel, int currentTokenValue, double t)
				throws IllegalActionException {
			sender.schedule(getTime().add(currentTokenValue * t), channel);
			// We remove the recorded received tokens as this information may not be up-to-date by the time we revisit this channel
			tokenPreviously.remove(channel);
			receivedOneAt.remove(channel);
		}

		/**
		 * Calculates the value of T by taking the difference between the time of reception of two tokens in the same SYNC phase.
		 * @param channel
		 * @param currentTokenValue
		 * @throws IllegalActionException
		 */
		private void calculateTWithSuccessiveTokens(int channel, int currentTokenValue) throws IllegalActionException {
			double deltaToken = valueOfTokenPreviously.get(channel) - currentTokenValue;
			 Time T = new Time(getDirector(), getTime().subtract(tokenPreviously.get(channel)).getDoubleValue() / deltaToken);
			 if(sink_t.get(channel) == null) sink_t.put(channel, T);
		}

		/**
		 *  We know that from the reception of a beacon with value one and the first beacon of the successive SYNC phase there are 12 T intervals.
		 * @param channelOfThisFire
		 * @throws IllegalActionException
		 */
		private void calculateTWithDivisionBy12(int channelOfThisFire) throws IllegalActionException {
			Time T = new Time(getDirector(), getTime().subtract(this.receivedOneAt.get(channelOfThisFire)).getDoubleValue() / 12.0);
			 sink_t.put(channelOfThisFire, T);
		}

		/**
		 *  Schedule for all future reception phases within the 60 seconds frame.
		 *  Register the channel as having been completely scheduled.
		 *  Moves on onto the next channel.
		 *  
		 * @param channelOfThisFire The channel to schedule
		 * @param currentTokenValue The current token value to calculate the reception phases
		 * @throws IllegalActionException
		 */
		private void scheduleAllSendsAndMoveOn(int channelOfThisFire, int currentTokenValue) throws IllegalActionException {
			scheduleChannelWithTheSender(currentTokenValue, channelOfThisFire);
			completelyScheduled.put(channelOfThisFire, true);
			channeler.goToNextChannel(completelyScheduled);
		}

		private void calculateN(int channelOfThisFire, int currentTokenValue) {
			if(sink_n.get(channelOfThisFire) == null) {
				 sink_n.put(channelOfThisFire, currentTokenValue);
			 }
		}
		
		private void setNIfReceived10(int channelOfThisFire, int currentTokenValue) {
			if(currentTokenValue == 10) {
				 sink_n.put(channelOfThisFire, currentTokenValue);
			 }
		}

		/**
		 * @return true when we have received a token previously and we have now received its immediate successor.
		 */
		private boolean gotTokenBefore() {
			return tokenPreviously.get(channeler.getCurrentChannel()) != null;
		}
		
		/**
		 * @return true when we have received a token previously with value one and we have now received its immediate successor.
		 */
		private boolean gotOneBefore() {
			return receivedOneAt.get(channeler.getCurrentChannel()) != null;
		}

		/**
		 * Request the sender to schedule all future reception phases of the current channel given the current time, current token value and channel parameters. 
		 * @param currentTokenValue
		 * @param channelOfThisFire
		 * @throws IllegalActionException
		 */
		private void scheduleChannelWithTheSender(int currentTokenValue, int channelOfThisFire) throws IllegalActionException {
			sender.scheduleCompletely(getTime(), currentTokenValue, 
						sink_t.get(channelOfThisFire), sink_n.get(channelOfThisFire), 
						channelOfThisFire);
		}
		
		/**
		 * Reads the token from the channel
		 * @return
		 * @throws IllegalActionException
		 */
		private int getFromChannel() throws IllegalActionException {
			return Integer.parseInt(beaconFramesInput.get(0).toString());
		}
		
		/**
		 * @param channel
		 * @return true if the channel's T and N have been calculated 
		 */
		private boolean areParametersFoundFor(int channel) {
			if(sink_t.get(channel) != null && sink_n.get(channel) != null) {
				return true;
			}
			return false;
		}
		
		/**
		 * @return true if T and N have been calculated for all channels
		 */
		private boolean areAllParametersKnown() {
			if(sink_n.size() == 5 && sink_t.size() == 5) {
				return true;
			}
			return false;
		}
		
		/**
		 * Returns the time of the system
		 * @return
		 */
		private Time getTime() {
			return getDirector().getModelTime();
		}	
		
		/**
		 * Determines if there is sufficient margin between time t and the times at which we have something scheduled already.
		 * The value 10 seconds is used because it is how long at most we need to wait for a beacon after switching to a channel at its
		 * minimum wake up time calculated assuming the channel T is 0.5
		 * If in reality the T is the maximum 1.5 the gap between the minimum wake up time and the maximum is |0.5*10 - 1.5*10| = 10
		 * @param t The time at which we would like to schedule an event
		 * @return true if there is enough margin
		 */
		private boolean isSchedulable(Time t) {
			for (Map.Entry<Time, Integer> entry : scheduled_channel.entrySet()) {
				Time time = entry.getKey();
				if(time.compareTo(getTime()) == -1) {
					// Ignore times that are in the past
					continue;
				}
				if(Math.abs(time.getDoubleValue() - t.getDoubleValue()) < 10.00001) {
					return false;
				}
			}
			return true;
		}
		
		/**
		 * 
		 * @return true if the the time passed between the reception of the current beacon and the last beacon on this channel is greater than 1.5 seocnds,
		 * meaning that we have been waiting through some of the sleeping phase of the channel.
		 * @throws IllegalActionException
		 */
		private boolean checkWhenLastTokenOnThisChannelAndCompareToWhenCurrentToken() throws IllegalActionException {
			// Time this beacon - time last beacon > 1.5 seconds
			return getTime().subtract(channeler.getTimeSwitchedToChannel()).compareTo(new Time(getDirector(), 1.5000001)) >= 1;
		}
		
		
}

          
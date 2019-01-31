package embs;

import com.ibm.saguaro.system.*;
import com.ibm.saguaro.logger.*;
/**
 * Main class that estimates T for the sinks, determines when a frame should be sent and sends it.
 * It does not attempt to calculate N and neither to schedule up-front two or more reception phases.
 * Instead it uses the current beacon value and the value of T to target the middle of the upcoming reception phase.
 */
public class Source {
	
	private static Radio radio = new Radio();;
	private static Timer  waitAfterTransmissionToChangeChannel;
	private static Timer[]  channelSenders = new Timer[3];
	private static Timer[] channelSwitches = new Timer[3];
   	private static byte[] xmit;
    private static long[] T = new long[3];
    private static long[] timeLastToken = new long[3];
    // Channels constants
    private static final byte[] channels_to_panid = {0x11, 0x12, 0x13};
    private static final byte[] short_to_panid = {0x11, 0x12, 0x13};
    
    // Used to switch back after changing channel for a send
    private static int oldChannel;
 
    // settings for Source
    private static byte panid = 0x11;
    private static byte address = 0x11;
    
    
	static {
		// The 3 frames are packed together in this single byte array, an index and a length is used in the transmit method to 
		xmit = MyUtils.prepareFrame(channels_to_panid, short_to_panid, panid, address);
		// Set up activities
		MyUtils.setUpChannelTimers(channelSenders); 
		MyUtils.setUpWaitChannelSwitch(channelSwitches);
		MyUtils.setUpRadio(radio);
		MyUtils.setUpWaitAfterTransmissionToChangeChannel(waitAfterTransmissionToChangeChannel);
		// We start listening to channel 0
		startListeningTo(0);
    }
	
	/**
	 * Called when a token is received from the sink to which our radio is tuned in 
	 * @param flags
	 * @param data
	 * @param len
	 * @param info
	 * @param time
	 * @return
	 */
	protected static int onReceive(int flags, byte[] data, int len, int info, long time) {
		if(data == null){
			// The reception phase of our radio has terminated. 
			// We do not need to do anything as we will have sheduled when to wake up again.
			return 0;
		}
		// The integer value contained in the beacon
    	int beaconNumber = (int)data[11]; 
		if(MyUtils.knowT(radio.getChannel(), T)) {
			// Deals with the recived beacon when the parameter T has already been estimated
			Source.onReceiveKnownT(flags, data, len, info, time, beaconNumber);
			return 0;
		} else {
			// Deals with the recived beacon when the parameter T is unknown
			Source.onReceiveUnkownT(flags, data, len, info, time, beaconNumber);
			return 0;
		}
	}

	/**
	 * Try to estimate T by subtracting the time at which to successive frames have been received.
	 * If it succeeds it schedule to send one frame targeting the middle of the reception phase of the sink.
	 * 
	 */
	private static int onReceiveUnkownT (int flags, byte[] data, int len, int info, long time, int beaconValue) {
		// The channel we received a beacon from
    	int ch = radio.getChannel();
    	if(timeLastToken[ch] != 0) { 
			T[ch] = Time.fromTickSpan(Time.MILLISECS, time - timeLastToken[ch]);
		} else {
			if(beaconValue == 1) {
				iterateChannel();
				return 0;
			} else {
				timeLastToken[ch] = time;
			}
		}
    	if(MyUtils.knowT(ch, Source.T)) {
    		// If we know T we schedule sending a frame for the next reception phase
    		scheduleAndIterate(beaconValue, ch);
		}
        return 0;
    }

	/**
	 * Schedules next send of a frame for reception phase and iterate tot he next channel
	 */
	private static int onReceiveKnownT (int flags, byte[] data, int len, int info, long time, int beaconValue) {
		int ch = radio.getChannel();
		scheduleAndIterate(beaconValue, ch);
		return 0;
	    	
    }
	
	private static void scheduleAndIterate(int beaconValue, int ch) {
		long midRXMillisecs = (beaconValue * T[ch]) + (T[ch] / 2);
		scheduleSend(midRXMillisecs);
		scheduleChannelSwitch(midRXMillisecs);
		iterateChannel();
	}
	/**
	 * Called to switch to the channel roughly when it will wake up. The purpose is to get a value of a beacon to 
	 * schedule the net reception phase and also to possibly sleep during the time we are waiting for the sink to wake up.
	 * We can not be 100% sure it will be the wake up moment because of clock drift.
	 * @param upcomingMidRx
	 * @return
	 */
	private static boolean scheduleChannelSwitch(long upcomingMidRx) {
		int ch = radio.getChannel();
		// Roughly when the channel will wake up + T/2
		long channelWakeUpTime = Time.toTickSpan(Time.MILLISECS, upcomingMidRx + (10 * T[ch]));
		long tempScheduledSwitch = channelWakeUpTime;
		if(Scheduler.canChannelSwitchBeScheduled(tempScheduledSwitch) == 0) {
			// Schedule of channel switch succeed the first time
			Scheduler.addScheduledChannelSwitch(ch, tempScheduledSwitch);
		} else {
			// Schedule of channel switch failed, we try to add an offset and see whether it will be schedulable
			tempScheduledSwitch = addOffset(tempScheduledSwitch);
			if(Scheduler.canChannelSwitchBeScheduled(tempScheduledSwitch) == 0) {
				// The schedule of channel switch succeeded at the second attempt
				Scheduler.addScheduledChannelSwitch(ch, channelWakeUpTime);
			} else {
				// We do not continue adding offsets because we may end up in the reception phase
				return false;
			} 
		}
		// Set the timer that will switch to the apporpiate channel
		channelSwitches[ch].setAlarmBySpan(tempScheduledSwitch);
		return true;
	}

	private static long addOffset(long tempScheduledSwitch) {
		// The method canChannelSwitchBeScheduled returns an offset we can conveniently use 
		long offset = Scheduler.canChannelSwitchBeScheduled(tempScheduledSwitch);
		// We add the duration of the maximum T to separate the time we are tying to schedule 
		// from the value it previously clashed with. 
		tempScheduledSwitch += MyUtils.abs(offset) + Time.toTickSpan(Time.MILLISECS, 1501);
		return tempScheduledSwitch;
	}
	/**
	 * Schedule the transmission of a frame to a sink by setting an alarm that when goes off executes the transmission.
	 * The right timer is obtained by addressing with the channel number an array of timers.
	 * @param midRXMillisecs When the send should be performed
	 */
	private static void scheduleSend(long midRXMillisecs) {
		channelSenders[radio.getChannel()].setAlarmBySpan(Time.toTickSpan(Time.MILLISECS, midRXMillisecs));
	}

	/**
	 * Iterates through channels by increasing an index.
	 * If a channel is scheduled to be switched to we do not iterate to it.
	 * To save power we stop reception mode of the radio if we determine that we do not need
	 * to iterate to a channel because switches have been schduled.
	 */
	private static void iterateChannel() {
		int nextChannel = radio.getChannel() + 1;
		nextChannel %= 3;
		for(int i = 0; i < 3; i++) {
			if(Scheduler.getScheduledTime(nextChannel) != 0) {
				// teh channel has been scheduled to be switched to 
				nextChannel += 1; // Increment
				nextChannel %= 3; // Wrap back to 0 if index > 2
			} else {
				// We found a channel that was not scheduled to be switched to and we start listening to it
				startListeningTo(nextChannel);
				return;
			}
		}
		// Stop reception to save enrgy/ avoid reading beacons we do not need to see
		stopRadioIfItIsReceiving();
		return; 
	}
	
	/**
	 * Stops reception of radio if it was receiving and tunes the radio to listen to the new channel
	 * @param channelTo
	 */
	private static void tuneRadioToChannel(int channelTo) {
		stopRadioIfItIsReceiving();
    	radio.setPanId(channels_to_panid[channelTo], false);
        radio.setChannel((byte)channelTo);
        radio.setShortAddr(address);
	}

	private static void stopRadioIfItIsReceiving() {
		if(radio.getState() == Radio.S_RXEN) {
			// If the radio was in a sate where it could receive, then stop reception
			radio.stopRx();
		}
	}
	/**
	 * Send a beacon frame
	 * @param param
	 * @param time
	 */
	static void sendToChannel(byte param, long time) {
		MyUtils.print(csr.s2b("Sent       To        a         channel"));
		// Sets the channel that we are going to switch to after sending
		oldChannel = radio.getChannel();
    	if (Source.radio.getChannel() != (int)param) {
    		// If the radio is not tuned in already with the target channel, then tune it 
    		tuneRadioToChannel((int)param);
    	} 
    	// We transmit using the policy as soon as possible, the byte array xmit is addressed using an offset 
    	// (a value from 0, 12 and 24) and the length of the frame which is 12
    	radio.transmit(Device.ASAP|Radio.TXMODE_POWER_MAX, xmit, (int)param * 12, 12, 0);
    	// We need a delay to switch channel correctly after a transmisison
    	waitAfterTransmissionToChangeChannel.setAlarmBySpan(Time.toTickSpan(Time.MILLISECS, 5));
    }
    /**
     * Tune the radio to a new channel and starts listening to it
     * @param channel
     */
    private static void startListeningTo(int channel) {
    	tuneRadioToChannel(channel);
    	// We wait 3 milliseconds before restarting the radio because it seems like it can cause issues restarting imemdiatelly
		radio.startRx(Device.ASAP, Time.currentTicks() + Time.toTickSpan(Time.MILLISECS,3), Time.currentTicks() + Time.toTickSpan(Time.SECONDS, 20));
	}
 
    /**
     * Timer callback, switches to the old channel 
     */
    protected static void switchToOldChannel(byte param, long time) {
    	if(knowTForAllChannles()) {
    		// If T has been calculated for all channels we do not need to switch back.
    		// We only need to switch back if there is a channel for which we have not estimated T otherwise the schedulign mechanism will deal with 
    		// channel switches.
    		return;
    	}
		startListeningTo(oldChannel);
	}
    /**
     * Timer callback, switch to channel specified by parameter, see setUpWaitChannelSwitch in MyUtils
     */
    protected static void switchToChannel(byte param, long time) {
    	Scheduler.clearScheduledChannelSwitch(param);
		startListeningTo(param);
	}
    /**
     * returns true if we know T for all channels
     * @return
     */
    public static boolean knowTForAllChannles() {
		for(int i = 0; i<3; i++) {
			if(T[i] == 0) {
				return false;
			}
		}
		return true;
	}
   
}
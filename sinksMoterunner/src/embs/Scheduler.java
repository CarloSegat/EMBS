package embs;

import com.ibm.saguaro.system.Time;
import com.ibm.saguaro.system.csr;
/**
 * Schedules channels switches. Keeps a list of switches that have been scheduled and uses it to check wheter
 * it is safe to schdule a new channel switch
 */
public class Scheduler {
	
	// When we switch to a channel using the schedule mechanism, we do it roughly when the sink wakes up and we are waiting for the first token of the SYNC.
	// The value 1510 has heuristically proven to work. 
	private static final int MIN_DELTA_BETWEEN_CHANNEL_SWITCHES = 1510;
	
	// Contains the time of the scheduled channel switch for the three channels
	private static long[] scheduledChannelSwitches = new long[3]; 
	
	/**
	 * Checks if we can schedule a channel switch.
	 * @param time
	 * @return True if we can schedule the switch.
	 */
	public static long canChannelSwitchBeScheduled(long time) {
    	  return canBeScheduled(time, scheduledChannelSwitches, MIN_DELTA_BETWEEN_CHANNEL_SWITCHES);
     }
	
	/**
	 * Compares the absolute value of the difference between a time value and every time value in a list with a threshold.
	 * @param time
	 * @param scheduledAlready
	 * @param delta
	 * @return
	 */
	public static long canBeScheduled(long time, long[] scheduledAlready, int delta) {
		long timeMillisec = Time.fromTickSpan(Time.MILLISECS, time);
	  	  for(int i = 0; i < scheduledAlready.length; i++) {
	  		  if(Time.fromTickSpan(Time.MILLISECS, MyUtils.abs(scheduledAlready[i] - time)) < delta) {
	  			  return scheduledAlready[i] - time;
	  		  }
	  	  }
	  	  return 0;
    }
	
	/**
	 * Ad scheduled channel to the list of scheduled channels.
	 * @param channel
	 * @param wannaGoBackHereAt
	 */
	public static void addScheduledChannelSwitch(int channel, long wannaGoBackHereAt) {
		scheduledChannelSwitches[channel] = wannaGoBackHereAt;
	}
	/**
	 * Return the time at which a channel is scheduled to be switched to	
	 * @param channel
	 * @return
	 */
	public static long getScheduledTime(int channel) {
		return scheduledChannelSwitches[channel];
	}
	/**
	 * Remove a channel from the list of scheduled channels
	 * @param channel
	 */
	public static void clearScheduledChannelSwitch(int channel) {
		scheduledChannelSwitches[channel] = 0;
	}
	
	
}
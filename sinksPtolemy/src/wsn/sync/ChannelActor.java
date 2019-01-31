package lsi.wsn.sync;

import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;

import javax.management.RuntimeErrorException;

import ptolemy.actor.IOPort;
import ptolemy.actor.NoRoomException;
import ptolemy.actor.TypedAtomicActor;
import ptolemy.actor.TypedIOPort;
import ptolemy.actor.util.Time;
import ptolemy.data.IntToken;
import ptolemy.data.type.BaseType;
import ptolemy.kernel.CompositeEntity;
import ptolemy.kernel.util.IllegalActionException;
import ptolemy.kernel.util.NameDuplicationException;

/** 
 * Controls changing the channel we are listening and sending to 
 * @author cs1566
 *
 */
public class ChannelActor extends TypedAtomicActor {
	
	// Output port for changing the channel
	protected TypedIOPort channelOutput;
	
	// Maps a time value to a channel to switch to 
	private Map<Time, Integer> timeToChannel;
	
	// Maps the times above to whether we should update the value of the variable indicating when we have switched to a channel.
	// The value will be false when we are switching channel for the purpose of sending a token, true when we switch to listen to the channel
	private Map<Time, Boolean> time_isUpdateTime;
	
	// When we switched to the current channel for listening. Used to determine if we have been waiting through the sleep phase 
	// and consequently in the calculations for the channel parameters
	private Time timeSwitchedToChannel;
	
	// The channel we are currently listenign and sending to 
	private int currentChannel;
	
	public Time getTimeSwitchedToChannel() {
		return timeSwitchedToChannel;
	}
	
	public void setTimeSwitchedToChannel(Time timeSwitchedToCurrentChannelWithoutSeeingAToken) {
		this.timeSwitchedToChannel = timeSwitchedToCurrentChannelWithoutSeeingAToken;
	}
	
	public int getCurrentChannel() {
		return currentChannel;
	}

	public ChannelActor(CompositeEntity container, String name) throws NameDuplicationException, IllegalActionException  {
	    super(container, name);
	    // Ports setup
	    channelOutput = new TypedIOPort(this, "channelOutput", false, true);
        channelOutput.setTypeEquals(BaseType.INT);
	}
	        
    public void initialize() throws IllegalActionException {
    	super.initialize();
    	// Start with the current channel
    	currentChannel = 11;
    	channelOutput.send(0, new IntToken(currentChannel));
    	// The time we started listening to the first channel is 0
    	this.timeSwitchedToChannel = new Time(getDirector(), 0.0);
    	// Initialisation of maps and 
    	timeToChannel = new HashMap<Time, Integer>();
    	time_isUpdateTime = new HashMap<Time, Boolean>();
    }

	public void fire() throws IllegalActionException{
		// Get the channel that we should be switching at this particular moment in time
		currentChannel = timeToChannel.get(getTime());
		// Update channel
		channelOutput.send(0, new IntToken(currentChannel));
		if(time_isUpdateTime.get(getTime())) {
			// This is executed when the channel switch is performed for reception purposes
			setTimeSwitchedToChannel(getTime());
		}
		// Removed the scheduled channel switch
		timeToChannel.remove(getTime());
    }
	
	/**
	 * Schedule a channel switch. Keeps track of the scheduled switches in a map and instruct the director to call the
	 * fire method at the specified time. This method should be used in such a way that the scheduled time is not present 
	 * already in the map of scheduled switches, otherwise it will overwrite an existing schedule.
	 * @param time
	 * @param channel
	 * @param isNotForFiring
	 * @throws IllegalActionException
	 */
	public void scheduleSwitchChannel(Time time, int channel, boolean isNotForFiring) throws IllegalActionException {
		timeToChannel.put(time, channel);
		time_isUpdateTime.put(time, isNotForFiring);
		getDirector().fireAt(this, time);
	}
	
	/**
	 * Switches the channel for reception
	 * @param channel
	 * @throws IllegalActionException
	 */
	public void switchChannelForReception(int channel) throws IllegalActionException {
		timeToChannel.put(getTime(), channel);
		time_isUpdateTime.put(getTime(), true);
		fire();// check if time when client calls switchChannel (say T) is the same used by this invocation of the fire method
	}
	
	/**
	 * Immediatelly switch to the channel and after a short while switches back to the old channel.
	 * @param channel
	 * @param channelToSwitchBack
	 * @throws IllegalActionException
	 */
	public void switchChannelForSending(int channel, int channelToSwitchBack) throws IllegalActionException {
		timeToChannel.put(getTime(), channel);
		time_isUpdateTime.put(getTime(), false);
		fire();// check if time when client calls switchChannel (say T) is the same used by this invocation of the fire method
		scheduleSwitchChannel(getTime().add(0.0000002), channelToSwitchBack, false);
	}
	
	
	/**
	 * Iterates through the channel for parameter estimation. If a channel does not need parameter estimation it skips it.
	 * @param completelyScheduled Map of channels for which parameters have been estimated, i.e. that have been scheduled 
	 * @throws IllegalActionException
	 */
	public void goToNextChannel(Map<Integer, Boolean> completelyScheduled) throws IllegalActionException {
		int next = currentChannel + 1; // Increment the channel
		if(next > 15) next = 11; // Wraps around
		if(!areAllSinksScheduled(completelyScheduled)) {
			while(completelyScheduled.get(next)) {
				// If the current channel has been scheduled we move to the next
				next += 1;
				if(next > 15) next = 11;
			}
			// We found a channel for which we do not know the parameters, i.e. is not completely schduled
			switchChannelForReception(next);
		}
		// If all channels are scheduled we do nothing
	}

	/**
	 * Returns true if all sinks have been schduled, i.e. parameters have been estimated
	 * @param completelyScheduled
	 * @return
	 */
	private boolean areAllSinksScheduled(Map<Integer, Boolean> completelyScheduled) {
		for (Entry<Integer, Boolean> entry : completelyScheduled.entrySet()) {
			if(entry.getValue()) {
				continue;
			} else {
				return false;
			}
		}
		return true;
	}
	
	private Time getTime() {
		return getDirector().getModelTime();
	}
}

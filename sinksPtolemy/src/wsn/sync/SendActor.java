package lsi.wsn.sync;

import java.util.HashMap;
import java.util.Map;

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
 * Sends frames to channels in the reception phase.
 * @author cs1566
 *
 */
public class SendActor extends TypedAtomicActor {
	
	protected TypedIOPort output;
	private Map<Time, Integer> timeToChannels;
	private ChannelActor channeler;
	private int channelToSwitchBack;
	private boolean isOutputChannelTheRightOne = false;
	
	public SendActor(CompositeEntity container, String name, ChannelActor channeler) throws NameDuplicationException, IllegalActionException  {
	    super(container, name);
	    // Output port for frames
	    output = new TypedIOPort(this, "output", false, true);
	    output.setTypeEquals(BaseType.INT);
	    // The sender needs the channeller to orchestrate the switching to the channel we are targeting and the switching back to the one we were listening to
	    this.channeler = channeler;
	}
	        
    public void initialize() throws IllegalActionException {
    		super.initialize();
    		timeToChannels = new HashMap<Time, Integer>();
    		isOutputChannelTheRightOne = false;
    }

	public void fire() throws IllegalActionException{
		if(isOutputChannelTheRightOne){
			// This executes when we have set the channel to be the one we are targeting
			isOutputChannelTheRightOne = false;
			this.output.send(0, new IntToken(69));
			return;
		}
		if(channeler.getCurrentChannel() != timeToChannels.get(getTime())) {
			// This executes when we need to switch to the channel we are targeting
			// We save the channel to switch back to
			channelToSwitchBack = channeler.getCurrentChannel();
			channeler.switchChannelForSending(timeToChannels.get(getTime()), channelToSwitchBack);
			// When this actor fires again it will have access to the channel it asked the channeller to switch to 
			getDirector().fireAt(this, getTime().add(0.0000001));
			isOutputChannelTheRightOne = true;
			return;
		} else {
			// This executes when we happen to be on the channel we are targeting, no channeller activity required
			this.output.send(0, new IntToken(69));
		}
    }
	
	/**
	 * Schedules a single fire to the specified channel at the specified time
	 * @param time
	 * @param sink
	 * @throws IllegalActionException
	 */
	public void schedule(Time time, int sink) throws IllegalActionException {
		while(timeToChannels.get(time) != null) {
			// If we had something scheduled already we simply increment it by a small offset.
			// This is okay as our calculations of reception phase referred to the beginning of it.
			time = time.add(0.005);
		} 
		timeToChannels.put(time, sink);
		getDirector().fireAt(this, time);
	}
	/**
	 * Schedules for all the reception phases the channel will go through from the current time to the end of the simulation
	 * @param timeWhenLastTokenReceived
	 * @param lastTokenN Value of last token
	 * @param sinkT Map of T values for sinks
	 * @param sinkN Map of N values for sink
	 * @param channel The channel to schedule
	 * @throws IllegalActionException
	 */
	public void scheduleCompletely(Time timeWhenLastTokenReceived, int lastTokenN, Time sinkT, int sinkN, int channel) throws IllegalActionException {
		double spanAfterReceive = sinkT.getDoubleValue() + (10 * sinkT.getDoubleValue()) + (sinkN * sinkT.getDoubleValue());
		Time nextRx =  new Time(getDirector(), timeWhenLastTokenReceived.getDoubleValue() + (sinkT.getDoubleValue() * lastTokenN));
		for(int i = 0; i < 10; i++){
			Time nextNextRx = nextRx.add(spanAfterReceive * i);
			if(nextNextRx.compareTo(getTime()) == -1 || nextNextRx.compareTo(new Time(getDirector(), 60.0)) >= 1){
				continue; // happened already or wont happen
			} else {
				this.schedule(nextNextRx, channel);
			}
		}		
	}
	
	private Time getTime() {
		return getDirector().getModelTime();
	}
}

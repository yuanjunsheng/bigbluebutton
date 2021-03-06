/** 
*
* BigBlueButton open source conferencing system - http://www.bigbluebutton.org/
*
* Copyright (c) 2010 BigBlueButton Inc. and by respective authors (see below).
*
* This program is free software; you can redistribute it and/or modify it under the
* terms of the GNU General Public License as published by the Free Software
* Foundation; either version 2.1 of the License, or (at your option) any later
* version.
*
* BigBlueButton is distributed in the hope that it will be useful, but WITHOUT ANY
* WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
* PARTICULAR PURPOSE. See the GNU General Public License for more details.
*
* You should have received a copy of the GNU General Public License along
* with BigBlueButton; if not, see <http://www.gnu.org/licenses/>.
* 
**/
package org.bigbluebutton.voiceconf.red5.media.transcoder;

import java.nio.FloatBuffer;
import java.util.Random;
import org.slf4j.Logger;
import org.red5.logging.Red5LoggerFactory;
import org.red5.server.api.IConnection;
import org.red5.server.api.Red5;
import org.red5.server.net.rtmp.RTMPMinaConnection;
import org.red5.app.sip.codecs.Codec;
import org.red5.app.sip.codecs.asao.CodecImpl;

public class NellySipToFlashTranscoderImp implements SipToFlashTranscoder {
    protected static Logger log = Red5LoggerFactory.getLogger(NellySipToFlashTranscoderImp.class, "sip");

    private static final int NELLYMOSER_CODEC_ID = 82;
    
    /**
     * The length of resulting L16 audio converted from 160-byte Ulaw audio. 
     */
    private static final int L16_AUDIO_LENGTH = 256;
    /**
     * The length of Nelly audio that gets sent to Flash Player.
     */
    private static final int NELLY_AUDIO_LENGTH = 64;
    /**
     * The length of received Ulaw audio.
     */
    private static final int ULAW_AUDIO_LENGTH = 160;
    /**
     * The maximum size of our processing buffer. 8 Ulaw packets (8x160 = 1280) yields 5 L16/Nelly audio (5x256 = 1280). 
     */
    private static final int MAX_BUFFER_LENGTH = 1280;
    
    /**
     * Buffer that contain L16 transcoded audio from Ulaw.
     */
    private final FloatBuffer l16Audio = FloatBuffer.allocate(MAX_BUFFER_LENGTH);
    /*
     * A view read-only buffer that keeps track of which part of the L16 buffer will be converted to Nelly.
     */
    private FloatBuffer viewBuffer;
    
    private final float[] tempL16Buffer = new float[ULAW_AUDIO_LENGTH];
    private float[] tempNellyBuffer = new float[L16_AUDIO_LENGTH]; 					
    private final byte[] nellyBytes = new byte[NELLY_AUDIO_LENGTH];
    
   	private float[] encoderMap;
    private Codec audioCodec = null;    
    
    private long timestamp = 0;
    private final static int TS_INCREMENT = 32; 	// Determined from PCAP traces.
    
    /**
     * The transcode takes a 160-byte Ulaw audio and converts it to a 160-float L16 audio. Whenever there is an
     * available 256-float L16 audio, that gets converted into a 64-byte Nelly audio. Therefore, 8 Ulaw packets
     * are needed to generate 5 Nelly packets.
     * @param audioCodec
     */
    public NellySipToFlashTranscoderImp(Codec audioCodec) {
    	this.audioCodec = audioCodec;    	    	
      	encoderMap = new float[64];
        Random rgen = new Random();
        timestamp = rgen.nextInt(1000);
        viewBuffer = l16Audio.asReadOnlyBuffer();
    }

	@Override
	public void transcode(byte[] audioData, TranscodedAudioDataListener listener) { 
		if (audioData.length != ULAW_AUDIO_LENGTH) {
			log.warn("Received corrupt audio. Got {}, expected {}.", audioData.length, ULAW_AUDIO_LENGTH);
			return;
		}
		
		// Convert Ulaw to L16
        int decodedBytes = audioCodec.codecToPcm(audioData, tempL16Buffer);

        // Store into the buffer
        l16Audio.put(tempL16Buffer);
        
        if ((l16Audio.position() - viewBuffer.position()) >= L16_AUDIO_LENGTH) {
        	// We have enough L16 audio to generate a Nelly audio.
        	// Get some L16 audio
        	viewBuffer.get(tempNellyBuffer);
        	// Convert it into Nelly
			encoderMap = CodecImpl.encode(encoderMap, tempNellyBuffer, nellyBytes);
			
			// Having done all of that, we now see if we need to send the audio or drop it.
			// We have to encode to build the encoderMap so that data from previous audio packet 
			// will be used for the next packet.			
			boolean sendPacket = true;			
			IConnection conn = Red5.getConnectionLocal();
			if (conn instanceof RTMPMinaConnection) {
				long pendingMessages = ((RTMPMinaConnection)conn).getPendingMessages();
				if (pendingMessages > 25) {   
					// Message backed up probably due to slow connection to client (25 messages * 20ms ptime = 500ms audio)
					sendPacket = false;
					log.info("Dropping packet. Connection {} congested with {} pending messages (~500ms worth of audio) .", conn.getClient().getId(), pendingMessages);
				}    					
			} 
				
			if (sendPacket) listener.handleTranscodedAudioData(nellyBytes, timestamp += TS_INCREMENT);			
        }
        
        if (l16Audio.position() == l16Audio.capacity()) {
        	// We've processed 8 Ulaw packets (5 Nelly packets), reset the buffers.
        	l16Audio.clear();
        	viewBuffer.clear();
        }
    }
		
	@Override 
    public int getIncomingEncodedFrameSize() {
    	return audioCodec.getIncomingEncodedFrameSize();
    }

	@Override
	public int getCodecId() {
		return NELLYMOSER_CODEC_ID;
	}
}


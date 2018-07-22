/*
 * Copyright 2014 Open Networking Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package de.uniba.ktr.ioannidis.onosApp;

import org.apache.felix.scr.annotations.*;
import org.onlab.packet.Ethernet;
import org.onlab.packet.IPv4;
import org.onlab.packet.TpPort;
import org.onosproject.core.ApplicationId;
import org.onosproject.core.CoreService;
import org.onosproject.net.DeviceId;
import org.onosproject.net.Host;
import org.onosproject.net.HostId;
import org.onosproject.net.PortNumber;
import org.onosproject.net.flow.*;
import org.onosproject.net.flow.criteria.PiCriterion;
import org.onosproject.net.flow.instructions.Instruction;
import org.onosproject.net.flow.instructions.Instructions;
import org.onosproject.net.flowobjective.DefaultForwardingObjective;
import org.onosproject.net.flowobjective.FlowObjectiveService;
import org.onosproject.net.flowobjective.ForwardingObjective;
import org.onosproject.net.host.HostService;
import org.onosproject.net.intent.HostToHostIntent;
import org.onosproject.net.intent.IntentService;
import org.onosproject.net.intent.IntentState;
import org.onosproject.net.intent.Key;
import org.onosproject.net.packet.*;
import org.onosproject.net.pi.model.PiMatchFieldId;
import org.onosproject.net.topology.TopologyService;
import org.slf4j.Logger;

import java.util.*;

import static org.slf4j.LoggerFactory.getLogger;

/**
 * Based on sample reactive forwarding application using intent framework provided by onos, this app blocks all traffic
 * initially and sets up intent(s) only when packets with a magicCookie are received.
 */
@Component(immediate = true)
public class AppComponent {

    private final Logger log = getLogger(getClass());

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected CoreService coreService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected TopologyService topologyService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected PacketService packetService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected IntentService intentService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected HostService hostService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected FlowRuleService flowRuleService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected FlowObjectiveService flowObjectiveService;

    private ReactivePacketProcessor processor = new ReactivePacketProcessor();
    private ApplicationId appId;

    private static final int DROP_RULE_TIMEOUT = 300;

    //These are the messages I'm looking for in the packets
    private static final byte[] magicCookieOn = "allowTraffic".getBytes();
    private static final byte[] magicCookieOff = "stopTraffic".getBytes();

    //Set of withdrawn states
    private static final EnumSet<IntentState> WITHDRAWN_STATES = EnumSet.of(IntentState.WITHDRAWN,
            IntentState.WITHDRAWING,
            IntentState.WITHDRAW_REQ);

    @Activate
    public void activate() {
        appId = coreService.registerApplication("de.uniba.ktr.ioannidis.onosApp");
        packetService.addProcessor(processor, PacketProcessor.director(2));

        TrafficSelector.Builder selector = DefaultTrafficSelector.builder();
        selector.matchEthType(Ethernet.TYPE_IPV4);


        //TODO: REACTIVE is desired, but then intents cannot be withdrawn, since the dns packets don't reach this app - request only dns?
        packetService.requestPackets(selector.build(), PacketPriority.REACTIVE, appId);
        log.info("Started");
    }

    @Deactivate
    public void deactivate() {
        packetService.removeProcessor(processor);
        processor = null;
        log.info("Stopped");
    }

    /**
     * Packet processor responsible for forwarding packets along their paths.
     */
    private class ReactivePacketProcessor implements PacketProcessor {

        @Override
        public void process(PacketContext context) {
            //Get needed information on packet, sender, receiver
            InboundPacket pkt = context.inPacket();
            Ethernet ethPkt = pkt.parsed();
            HostId srcId = HostId.hostId(ethPkt.getSourceMAC());
            HostId dstId = HostId.hostId(ethPkt.getDestinationMAC());
            Host dst = hostService.getHost(dstId);

            // If already handled or packet null - return
            if (context.isHandled() || ethPkt == null) {
                return;
            }

            // If Destination null, flood and bail.
            if (dst == null) {
                flood(context);
                return;
            }

            //Allow all ARP by flooding
            if (ethPkt.getEtherType() == Ethernet.TYPE_ARP) {
                log.info("Flooding ARP from " + srcId + " to " + dstId);
                flood(context);
                return;
            }

            //Check for our cookies/messages and install/withdraw intent
            boolean cookieFoundOn = search(pkt.unparsed().array(), magicCookieOn);
            boolean cookieFoundOff = search(pkt.unparsed().array(), magicCookieOff);

            if (cookieFoundOff) {
                log.info("--- StopTraffic - Cookie was found, stopping intent between: " + srcId + " and " + dstId + "\n");
                blockConnectivity(srcId, dstId);

                //If intent is in installed state / has already been unlocked, forward packet
            } else if (intentUnlocked(srcId, dstId)) {
                forwardPacketToDst(context, dst);
                log.info("Intent unlocked, forwarding packet...");
                return;
            }


            if (cookieFoundOn) {
                log.info("--- AllowTraffic - Cookie was found, setting up intent between: " + srcId + " and " + dstId + "\n");
                log.info("Payload 1: " + ethPkt.getPayload().toString());
                log.info("Payload 2: " + ethPkt.getPayload().getPayload().toString());
                log.info("Payload 3: " + ethPkt.getPayload().getPayload().getPayload().toString());

                setUpConnectivity(context, srcId, dstId);

            }
        }

        /**
         * Looks for an intent between specified source and destination, and if it exists and is in installed state, it withdraws it.
         *
         * @param srcId Source Id of the intent
         * @param dstId Destination Id of the intent
         */
        private void blockConnectivity(HostId srcId, HostId dstId) {
            Key key = getKey(srcId, dstId);
            HostToHostIntent intent = (HostToHostIntent) intentService.getIntent(key);
            IntentState state = intentService.getIntentState(key);
            if (intent != null && state == IntentState.INSTALLED) {
                intentService.withdraw(intent);
            }
        }

        /**
         * Checks whether intent is unlocked and exists between specified source and destination
         *
         * @param srcId
         * @param dstId
         * @return True if intentn in installed state, false if non existant or not in installed state
         */
        private boolean intentUnlocked(HostId srcId, HostId dstId) {
            Key key = getKey(srcId, dstId);
            HostToHostIntent intent = (HostToHostIntent) intentService.getIntent(key);
            intentService.getIntentState(key);
            if (intent == null || intentService.getIntentState(key) != IntentState.INSTALLED) {
                return false;
            } else {
                return true;
            }

        }


        /**
         * Looks for a byte array (needle) in another byte array (haystack)
         *
         * @param haystack
         * @param needle
         * @return true if needle in haystack
         */
        //TODO: Attribution / reworking
        public boolean search(byte[] haystack, byte[] needle) {
            //convert byte[] to Byte[]
            Byte[] searchedForB = new Byte[needle.length];
            for (int x = 0; x < needle.length; x++) {
                searchedForB[x] = needle[x];
            }

            int idx = -1;

            //search:
            Deque<Byte> q = new ArrayDeque<Byte>(haystack.length);
            for (int i = 0; i < haystack.length; i++) {
                if (q.size() == searchedForB.length) {
                    //here I can check
                    Byte[] cur = q.toArray(new Byte[]{});
                    if (Arrays.equals(cur, searchedForB)) {
                        //found!
                        idx = i - searchedForB.length;
                        break;
                    } else {
                        //not found
                        q.pop();
                        q.addLast(haystack[i]);
                    }
                } else {
                    q.addLast(haystack[i]);
                }
            }
            return idx >= 0;
        }
    }

    /**
     * Floods current in packet of context on all ports
     *
     * @param context
     */
    private void flood(PacketContext context) {
        if (topologyService.isBroadcastPoint(topologyService.currentTopology(),
                context.inPacket().receivedFrom())) {
            packetOut(context, PortNumber.FLOOD);
        } else {
            context.block();
        }
    }

    /**
     * Sends packet of context to specified port
     *
     * @param context
     * @param portNumber
     */
    private void packetOut(PacketContext context, PortNumber portNumber) {
        context.treatmentBuilder().setOutput(portNumber);
        context.send();
    }

    /**
     * This nethod forwards packet to destination host
     *
     * @param context
     * @param dst
     */
    private void forwardPacketToDst(PacketContext context, Host dst) {
        TrafficTreatment treatment = DefaultTrafficTreatment.builder().setOutput(dst.location().port()).build();
        OutboundPacket packet = new DefaultOutboundPacket(dst.location().deviceId(),
                treatment, context.inPacket().unparsed());
        packetService.emit(packet);
    }


    /**
     * Sets up intetns between source and destination hosts for following communication. Checks for and deals with existence of intent, Failed/Withdrawn states.
     *
     * @param context
     * @param srcId
     * @param dstId
     */
    private void setUpConnectivity(PacketContext context, HostId srcId, HostId dstId) {
        TrafficSelector.Builder selector = DefaultTrafficSelector.builder();
        TrafficTreatment treatment = DefaultTrafficTreatment.emptyTreatment();

        openBackChannel(context);

        Key key = getKey(srcId, dstId);
        HostToHostIntent intent = (HostToHostIntent) intentService.getIntent(key);
        // TODO handle the FAILED state
        if (intent != null) {
            if (WITHDRAWN_STATES.contains(intentService.getIntentState(key))) {
                HostToHostIntent hostIntent = HostToHostIntent.builder()
                        .appId(appId)
                        .key(key)
                        .one(srcId)
                        .two(dstId)
                        .selector(selector.build())
                        .treatment(treatment)
                        .build();

                intentService.submit(hostIntent);
            } else if (intentService.getIntentState(key) == IntentState.FAILED) {

                TrafficSelector objectiveSelector = DefaultTrafficSelector.builder()
                        .matchEthSrc(srcId.mac()).matchEthDst(dstId.mac()).build();

                TrafficTreatment dropTreatment = DefaultTrafficTreatment.builder()
                        .drop().build();

                ForwardingObjective objective = DefaultForwardingObjective.builder()
                        .withSelector(objectiveSelector)
                        .withTreatment(dropTreatment)
                        .fromApp(appId)
                        .withPriority(intent.priority() - 1)
                        .makeTemporary(DROP_RULE_TIMEOUT)
                        .withFlag(ForwardingObjective.Flag.VERSATILE)
                        .add();

                flowObjectiveService.forward(context.outPacket().sendThrough(), objective);
            }

        } else if (intent == null) {
            HostToHostIntent hostIntent = HostToHostIntent.builder()
                    .appId(appId)
                    .key(key)
                    .one(srcId)
                    .two(dstId)
                    .selector(selector.build())
                    .treatment(treatment)
                    .build();

            intentService.submit(hostIntent);
        }

    }

    /**
     * This method establishes a flow from the device where the packet that triggered an intent creation came from, to
     * the controller with a priorityy higher than the other intents. The selector matches udp packets with dst port 53,
     * this should target dns packets only that are used for intent setup and teardown.
     *
     * @param context
     */
    private void openBackChannel(PacketContext context) {
        TrafficSelector.Builder selector = DefaultTrafficSelector.builder();
        TrafficTreatment.Builder treatment = DefaultTrafficTreatment.builder();

        treatment.setOutput(PortNumber.CONTROLLER);

        //Match UDP Packets with destination port 53
        selector.matchEthType(Ethernet.TYPE_IPV4);
        selector.matchIPProtocol(IPv4.PROTOCOL_UDP);
        selector.matchUdpDst(TpPort.tpPort(53));
        //Match port
        log.info("PORT OF INCOMING PACKET : " + context.inPacket().receivedFrom().port());
        selector.matchInPort(context.inPacket().receivedFrom().port());
        selector.matchEthSrc(context.inPacket().parsed().getSourceMAC());
        /*
        selector.matchUdpSrc(TpPort.tpPort(53));
        selector.matchUdpDst(TpPort.tpPort(853));
        selector.matchUdpSrc(TpPort.tpPort(853));
        */

        //Match port of incoming packet
        selector.matchInPort(context.inPacket().receivedFrom().port());
        selector.matchEthSrc(context.inPacket().parsed().getSourceMAC());

        //Install Flow in device the package came from, this should be the one closest to the host,
        //the priority must be highest in the table of the device
        FlowRule flowRule = DefaultFlowRule.builder()
                .withSelector(selector.build())
                .withTreatment(treatment.build())
                .forDevice(context.inPacket().receivedFrom().deviceId())
                .withPriority(40001)
                .makePermanent()
                .fromApp(appId)
                .build();

        flowRuleService.applyFlowRules(flowRule);

    }

    /**
     * Method for key generation of intent using source, destination ids and the app id
     *
     * @param srcId
     * @param dstId
     * @return
     */
    private Key getKey(HostId srcId, HostId dstId) {
        Key key;
        if (srcId.toString().compareTo(dstId.toString()) < 0) {
            key = Key.of(srcId.toString() + dstId.toString(), appId);
        } else {
            key = Key.of(dstId.toString() + srcId.toString(), appId);
        }
        return key;
    }
}

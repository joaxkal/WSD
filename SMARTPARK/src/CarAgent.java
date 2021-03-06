import jade.core.AID;
import jade.core.Agent;
import jade.core.behaviours.Behaviour;
import jade.core.behaviours.CyclicBehaviour;
import jade.core.behaviours.TickerBehaviour;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static utils.MapState.calculatePathBFS;
import static utils.MapState.calculatePathBFSLength;
import static utils.agentUtils.*;


public class CarAgent extends Agent {

    //Agent's location.
    private int[] agentLocation = {0, 0};

    // for TEST: change direction after X seconds and cause Cancelation
    private long creationTime = System.currentTimeMillis();
    private int[] farAwayPlace = {20,40};
    private long TIME_BEFORE_MOVING_AWAY = 15000;
    private boolean isZombie=false;

    //for TEST: cancel reservation by car agent
    private  long TIME_BEFORE_CANCELLATION = 99990000;

    private Map<AID, int[]> parkingAgentLocations = new HashMap<AID, int[]>();
    // List of other agents in the container.
    private AID[] parkingAgents = {
            new AID("parking1", AID.ISLOCALNAME),
            //new AID("parking2", AID.ISLOCALNAME)
    };

    private boolean isUpdateListOfParkingsDone = false;
    private boolean isCallForParkingOffersDone = false;
    private boolean isApproacher = false;

    private AID parkingTarget;
    private ArrayList<int[]> pathToParking;

    private boolean hasCarTracker = false;

    private static String consoleIndentation = "\t\t\t";

    private boolean isConnectedToDatabase = false;

    protected void setup() {
        // Print a welcome message.
        System.out.println("Hello " + getAID().getName() + " is ready.");

//        agentLocation = initializeParkingLocation();
        agentLocation = initializeCarLocation();
        // Send position to the middleware server.
        try {
            sendCarData(getAID().getName(), "car", Arrays.toString(agentLocation));
            isConnectedToDatabase = true;
        } catch (Exception e) {
            System.out.println("Database ERROR");
        }

        addBehaviour(new sendReservationInfo());
        addBehaviour(new UpdateListOfParkings());
        addBehaviour(new CallForParkingOffers());
        addBehaviour(new ListenForLocationSubscriptionFromCarTracker());
        // Add a TickerBehaviour that sends location to car tracker every 5 seconds.
        addBehaviour(new TickerBehaviour(this, 2000) {
            @Override
            protected void onTick() {
                myAgent.addBehaviour(new SendLocationInfo());
            }
        });
        addBehaviour(new TickerBehaviour(this, 2000) {
            @Override
            protected void onTick() {
                myAgent.addBehaviour(new ListenForLocationCancelSubscriptionFromCarTracker());
            }
        });
//        addBehaviour(new ListenForLocationCancelSubscriptionFromCarTracker());
//
        addBehaviour(new CancelClientReservation());
        addBehaviour(new ReceiveCancelReservationIncreaseDistance());
    }

    protected void takeDown() {
        freeCarLocation(agentLocation);
        System.out.println("Car-agent " + getAID().getName() + " terminating.");
        if (isConnectedToDatabase) {
            try {
                removeAgentFromDatabase(getAID().getName());
            } catch (Exception e) {
                System.out.println("Database ERROR");
            }
        }
    }

    /**
     * Receive and cache locations of parking agents.
     * Part of MapParking Protocol.
     */
    private class UpdateListOfParkings extends Behaviour {

        private MessageTemplate mt; // The template to receive replies
        private int repliesCnt = 0;
        private int step = 0;

        public void action() {
            switch (step) {
                case 0:
                    ACLMessage msg = new ACLMessage(ACLMessage.INFORM_REF);

                    for (AID parkingAgent : parkingAgents) msg.addReceiver(parkingAgent);

                    msg.setConversationId("update-location");
                    msg.setReplyWith("inform_ref" + System.currentTimeMillis()); // Unique value
                    myAgent.send(msg);

                    // Prepare the template to get replies.
                    mt = MessageTemplate.and(MessageTemplate.MatchConversationId("update-location"),
                            MessageTemplate.MatchInReplyTo(msg.getReplyWith()));
                    step = 1;
                    System.out.println(myAgent.getName() + consoleIndentation + "Sent INFORM_REF to parking agents. Waiting for replies...");
                    break;
                case 1:
                    // Receive all locations from parking agents.
                    ACLMessage reply = myAgent.receive(mt);
                    if (reply != null) {
                        // Reply received
                        if (reply.getPerformative() == ACLMessage.INFORM) {
                            AID sender = reply.getSender();
                            String location = reply.getContent();
                            parkingAgentLocations.put(sender, parseLocation(location));
                        }
                        repliesCnt++;
                        if (repliesCnt >= parkingAgents.length) {
                            // We received all replies so print locations.
                            System.out.println(myAgent.getName() + consoleIndentation + "Received locations from all parking agents.");
                            for (AID parkingAgent : parkingAgents) {
                                System.out.println(myAgent.getName() + consoleIndentation + "Location of " + parkingAgent.getName() + " is " + Arrays.toString(parkingAgentLocations.get(parkingAgent)));
                            }
                            step = 2;
                            isUpdateListOfParkingsDone = true;
                        }
                    } else {
                        // This method marks the behaviour as "blocked" so that agent does not
                        // schedule if for execution anymore.
                        // When a new message is inserted in the queue behaviour will automatically unblock itself.
                        block();
                    }
                    break;
            }
        }

        public boolean done() { // if we return true this behaviour will end its cycle
            return step == 2;
        }
    }

    /**
     * Make a reservation for a parking space.
     * Part of PlaceReservation Protocol.
     * <p>
     * Case 0-1 - CallForParkingOffers action.
     * Case 2-3 - AcceptParkingOffer action.
     */
    private class CallForParkingOffers extends Behaviour {
        private MessageTemplate mt; // The template to receive replies
        private int step = 0;
        private int repliesCnt = 0;
        private AID closestParking;
        private Map<AID, Boolean> parkingAgentAvailability = new HashMap<AID, Boolean>();

        public void action() {
            if (isUpdateListOfParkingsDone) {
                switch (step) {
                    case 0:
                        //Send the cfp to parking agents.
                        ACLMessage cfp = new ACLMessage(ACLMessage.CFP);

                        for (AID parkingAgent : parkingAgents) cfp.addReceiver(parkingAgent);

                        cfp.setConversationId("offer-place");
                        cfp.setReplyWith("cfp" + System.currentTimeMillis()); // Unique value.
                        System.out.println(myAgent.getName() + consoleIndentation + "Sent CFP to parking agents. Waiting for replies...");
                        myAgent.send(cfp);

                        //Prepare the template to get proposals.
                        mt = MessageTemplate.and(MessageTemplate.MatchConversationId("offer-place"),
                                MessageTemplate.MatchInReplyTo(cfp.getReplyWith()));
                        step = 1;
                        break;
                    case 1:
                        //Receive info about availability of parking spaces.
                        ACLMessage reply = myAgent.receive(mt);

                        if (reply != null) {
                            //Reply received.
                            if (reply.getPerformative() == ACLMessage.PROPOSE) {
                                AID sender = reply.getSender();
                                parkingAgentAvailability.put(sender, true);
                            } else {
                                parkingAgentAvailability.put(reply.getSender(), false);
                            }

                            repliesCnt++;
                            if (repliesCnt >= parkingAgents.length) {

                                //Shortest path between car and parking.
                                int shortestDistance = Integer.MAX_VALUE;

                                for (AID parkingAgent : parkingAgents) {
                                    if (parkingAgentAvailability.get(parkingAgent)) {
                                        int distanceToParking = calculatePathBFSLength(agentLocation, parkingAgentLocations.get(parkingAgent));

                                        if (distanceToParking < shortestDistance) {
                                            shortestDistance = distanceToParking;
                                            closestParking = parkingAgent;
                                        }
                                        System.out.println(myAgent.getName() + consoleIndentation + "Location " + parkingAgent.getName() + " " + Arrays.toString(parkingAgentLocations.get(parkingAgent)) + " is available for reservation.");
                                    } else {
                                        System.out.println(myAgent.getName() + consoleIndentation + "Location " + parkingAgent.getName() + " " + Arrays.toString(parkingAgentLocations.get(parkingAgent)) + " is occupied.");
                                    }
                                }
                                if (shortestDistance != Integer.MAX_VALUE) {
                                    step = 2;
                                    System.out.printf((myAgent.getName() + consoleIndentation + "Best Location: " + Arrays.toString(parkingAgentLocations.get(closestParking)) + " and shortestDistance is %d \n"), shortestDistance);
                                } else {
                                    // Repeat this behaviour from step 0.
                                    step = 0;
                                    repliesCnt = 0;
                                    closestParking = null;
                                    System.out.println(myAgent.getName() + consoleIndentation + "No parking available for reservation");
                                    myAgent.doWait(2000);
                                }
                            }
                        } else {
                            // This method marks the behaviour as "blocked" so that agent does not
                            // schedule if for execution anymore.
                            // When a new message is inserted in the queue behaviour will automatically unblock itself.
                            block();
                        }
                        break;
                    case 2:
                        //Ask for reservation at the parking which provided the best offer.
                        ACLMessage reservation = new ACLMessage(ACLMessage.ACCEPT_PROPOSAL);

                        reservation.addReceiver(closestParking);
                        reservation.setConversationId("offer-place");
                        reservation.setReplyWith("reservation" + System.currentTimeMillis());
                        myAgent.send(reservation);

                        //Prepare the template to get the reply
                        mt = MessageTemplate.and(MessageTemplate.MatchConversationId("offer-place"),
                                MessageTemplate.MatchInReplyTo(reservation.getReplyWith()));
                        step = 3;
                        break;
                    case 3:
                        reply = myAgent.receive(mt);
                        if (reply != null) {
                            //Receive the place reservation order reply.
                            if (reply.getPerformative() == ACLMessage.INFORM) {
                                // Reservation successful.
                                parkingTarget = reply.getSender();
                                isCallForParkingOffersDone = true;
                                System.out.println(myAgent.getName() + consoleIndentation + "Place reserved at:" + parkingTarget.getName());
                                step = 4;
                            } else {
                                //Reservation failed.
                                System.out.println(myAgent.getName() + consoleIndentation + "Failure. Parking is occupied.");

                                // Retry.
                                step = 0;
                                repliesCnt = 0;
                                closestParking = null;
                                myAgent.doWait(2000);
                            }
                        } else {
                            block();
                        }
                        break;
                }
            }
        }

        public boolean done() { // if we return true this behaviour will end its cycle
            return step == 4;
        }
    }

    /**
     * Send information about our reservation to Parking (CarTracker role).
     * Part of TrackReservation protocol.
     */
    private class sendReservationInfo extends Behaviour {
        private MessageTemplate mt;
        private int step = 0;

        public void action() {
            if (isCallForParkingOffersDone) {
                switch (step) {
                    case 0:
                        ACLMessage msg = new ACLMessage(ACLMessage.INFORM);

                        msg.addReceiver(parkingTarget);
                        msg.setConversationId("send-reservation-info");
                        msg.setReplyWith("inform" + System.currentTimeMillis()); // Unique value.
                        msg.setContent("My ID " + myAgent.getAID() + " . My parking: " + parkingTarget);

                        System.out.println(myAgent.getName() + consoleIndentation + "Sent reservation info to " + parkingTarget.getName());
                        myAgent.send(msg);
                        isApproacher = true;

                        // Prepare the template to get replies.
                        mt = MessageTemplate.and(MessageTemplate.MatchConversationId("send-reservation-info"),
                                MessageTemplate.MatchInReplyTo(msg.getReplyWith()));

                        step = 1;
                        break;
                    case 1:
                        ACLMessage reply = myAgent.receive(mt);
                        if (reply != null) {
                            if (reply.getPerformative() == ACLMessage.CONFIRM) {
                                // We got confirmation...
                                step = 2;
                                break;
                            }
                        } else {
                            block();
                        }
                }
            }
        }

        public boolean done() { // if we return true this behaviour will end its cycle
            return step == 2;
        }
    }

    /**
     * Part of TrackCar protocol.
     */
    private class ListenForLocationSubscriptionFromCarTracker extends CyclicBehaviour {

        public void action() {
            MessageTemplate mt = MessageTemplate.and(MessageTemplate.MatchPerformative(ACLMessage.SUBSCRIBE),
                    MessageTemplate.MatchConversationId("send-subscription-request"));
            ACLMessage msg = myAgent.receive(mt);
            if (isApproacher && !hasCarTracker) {
                if (msg != null) {
                    hasCarTracker = true;
                    // for TEST: change direction after 20 seconds

                        pathToParking = calculatePathBFS(agentLocation, parkingAgentLocations.get(parkingTarget));
                     // pathToParking = calculatePathBFS(agentLocation, farAwayPlace);

                    for (int[] path : pathToParking) {
                        // System.out.println(Arrays.toString(path));
                    }
                    System.out.println(myAgent.getName() + consoleIndentation + parkingTarget.getName() + "has subscribed for info about my location");
                } else {
                    block();
                }
            }
        }
    }

    private class ListenForLocationCancelSubscriptionFromCarTracker extends Behaviour {

        public void action() {
            MessageTemplate mt = MessageTemplate.and(MessageTemplate.MatchPerformative(ACLMessage.CANCEL), MessageTemplate.MatchConversationId("send-subscription-cancel"));
            ACLMessage msg = myAgent.receive(mt);
            if (hasCarTracker && msg != null) {
                hasCarTracker = false;
                System.out.println(myAgent.getName() + consoleIndentation + "Received Cancel Subscription info from + " + parkingTarget.getName() + ". I stopped sending info...");
            } else {
                block();
            }
        }

        public boolean done() {
            return true;
        }
    }

    /**
     * Send information about our current location to the car tracker.
     * Part of TrackCar protocol.
     */
    private class SendLocationInfo extends Behaviour {

        public void action() {
//            boolean carHasMoved = oldAgentLocation[0] != agentLocation[0] && oldAgentLocation[1] != agentLocation[1];
            if (hasCarTracker || isZombie) {
                int[] parkingLocation = parkingAgentLocations.get(parkingTarget);
//                System.out.println(Arrays.toString(parkingLocation));
//                if (agentLocation[0] == parkingLocation[0] && agentLocation[1] == parkingLocation[1] ) {
//                    myAgent.addBehaviour(new CancelClientReservation());
//                    return;
//                }
                if (pathToParking.size() > 0) {

                    if ((System.currentTimeMillis() - creationTime) > TIME_BEFORE_MOVING_AWAY) {
                        pathToParking = calculatePathBFS(agentLocation, farAwayPlace);
                        isZombie=true;
                    }

                    agentLocation = pathToParking.remove(0);
                    if (isConnectedToDatabase) {
                        try {
                            sendCarData(getAID().getName(), "car", Arrays.toString(agentLocation));
                        } catch (Exception e) {
                            System.out.println("Database ERROR");
                        }
                    }
                }
                ACLMessage inform = new ACLMessage(ACLMessage.INFORM);

                inform.addReceiver(parkingTarget);
                inform.setConversationId("send-location-info");
                inform.setReplyWith("inform" + System.currentTimeMillis()); // Unique value.
                inform.setContent(Arrays.toString(agentLocation));
//                System.out.println(myAgent.getName() + consoleIndentation + "Sent my location: " + Arrays.toString(oldAgentLocation) + " to " + parkingTarget.getName());
                myAgent.send(inform);
            } else {
                block();
            }
        }

        public boolean done() {
            return true;
        }
    }

    /**
     * Cancel a reservation for a parking space.
     * Part of CancelReservation Protocol.
     */
    private class CancelClientReservation extends Behaviour {
            private MessageTemplate mt; // The template to receive replies
            private int step = 0;
            private long startTime = System.currentTimeMillis();

            public void action() {
                if (hasCarTracker) {
                    switch (step) {
                        case 0:
                            if (System.currentTimeMillis() - startTime > TIME_BEFORE_CANCELLATION) {
                                step = 1;
                            }
                            break;
                        case 1:
                            //Send a request to cancel reservation.
                            ACLMessage cancel = new ACLMessage(ACLMessage.CANCEL);
                            cancel.addReceiver(parkingTarget);
                            cancel.setConversationId("cancel-reservation");
                            cancel.setReplyWith("cancel" + System.currentTimeMillis()); // Unique value.
                            System.out.println(myAgent.getName() + consoleIndentation + "Sent CANCEL to target parking.");
                            myAgent.send(cancel);

                            //Prepare the template to get proposals.
                            mt = MessageTemplate.and(MessageTemplate.MatchConversationId("cancel-reservation"),
                                    MessageTemplate.MatchInReplyTo(cancel.getReplyWith()));
                            step = 2;
                            break;
                        case 2:
                            ACLMessage reply = myAgent.receive(mt);
                            if (reply != null) {
                                //Receive the place reservation order reply.
                                if (reply.getPerformative() == ACLMessage.CONFIRM) {
                                    // Reservation successful.
                                    isCallForParkingOffersDone = false;
                                    hasCarTracker = false;
                                    parkingTarget = null;
                                    isApproacher = false;
                                    pathToParking = null;
                                    System.out.println(myAgent.getName() + consoleIndentation + "Reservation cancelled.");
                                    step = 3;
                                }
                            } else {
                                block();
                            }
                            break;
                    }
                }
            }

        public boolean done() { // if we return true this behaviour will end its cycle
            return step == 3;
        }
    }


    private class ReceiveCancelReservationIncreaseDistance extends Behaviour {
        private int step = 0;
        MessageTemplate mt = MessageTemplate.and(MessageTemplate.MatchPerformative(ACLMessage.CANCEL),
                MessageTemplate.MatchConversationId("cancel-reservation-ct"));
        public void action() {
            if (hasCarTracker) {
                switch (step) {
                    case 0:
                        ACLMessage reply = myAgent.receive(mt);
                        if (reply != null) {
                            //Receive the place reservation order reply.

                                // Reservation successful.
                                isCallForParkingOffersDone = false;
                                hasCarTracker = false;
                                parkingTarget = null;
                                isApproacher = false;
                                System.out.println(myAgent.getName() + consoleIndentation + "Reservation cancelled by car tracker.");
                                step = 1;

                        } else {
                            block();
                        }
                        break;
                }
            }
        }

        public boolean done() { // if we return true this behaviour will end its cycle
            return step == 1;
        }
    }

}



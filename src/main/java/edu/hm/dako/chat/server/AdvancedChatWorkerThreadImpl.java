package edu.hm.dako.chat.server;

/**
 * Worker-Thread zur serverseitigen Bedienung einer Session mit einem Client.
 * Jedem Chat-Client wird serverseitig ein Worker-Thread zugeordnet.
 * 
 * @author
 *
 */

import edu.hm.dako.chat.common.ChatPDU;
import edu.hm.dako.chat.common.ClientConversationStatus;
import edu.hm.dako.chat.common.ClientListEntry;
import edu.hm.dako.chat.common.ExceptionHandler;
import edu.hm.dako.chat.connection.Connection;
import edu.hm.dako.chat.connection.ConnectionTimeoutException;
import edu.hm.dako.chat.connection.EndOfFileException;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import java.util.Vector;

public class AdvancedChatWorkerThreadImpl extends AbstractWorkerThread {

    private static Log log = LogFactory.getLog(AdvancedChatWorkerThreadImpl.class);

    public AdvancedChatWorkerThreadImpl(Connection con, SharedChatClientList clients,
                                      SharedServerCounter counter, ChatServerGuiInterface serverGuiInterface) {

        super(con, clients, counter, serverGuiInterface);
    }

    @Override
    public void run() {
        //log.debug("ChatWorker-Thread erzeugt, Threadname: " + Thread.currentThread().getName());
        while (!finished && !Thread.currentThread().isInterrupted()) {
            try {
                // Warte auf naechste Nachricht des Clients und fuehre
                // entsprechende Aktion aus
                handleIncomingMessage();
            } catch (Exception e) {
                //log.error("Exception waehrend der Nachrichtenverarbeitung");
                ExceptionHandler.logException(e);
            }
        }
        //log.debug(Thread.currentThread().getName() + " beendet sich");
        closeConnection();
    }

    @Override
    protected void sendLoginListUpdateEvent(ChatPDU pdu) {

        // Liste der eingeloggten bzw. sich einloggenden User ermitteln
        Vector<String> clientList = clients.getRegisteredClientNameList();

        //log.debug("Aktuelle Clientliste, die an die Clients uebertragen wird: " + clientList);

        pdu.setClients(clientList);

        Vector<String> clientList2 = clients.getClientNameList();
        for (String s : new Vector<String>(clientList2)) {
            //log.debug("Fuer " + s+ " wird Login- oder Logout-Event-PDU an alle aktiven Clients gesendet");

            ClientListEntry client = clients.getClient(s);
            try {
                if (client != null) {

                    client.getConnection().send(pdu);
                    //log.debug("Login- oder Logout-Event-PDU an " + client.getUserName() + " gesendet");
                    clients.incrNumberOfSentChatEvents(client.getUserName());
                    eventCounter.getAndIncrement();
                    //log.debug(userName + ": EventCounter bei Login/Logout erhoeht = "+ eventCounter.get() + ", ConfirmCounter = " + confirmCounter.get());
                }
            } catch (Exception e) {
                //log.debug("Senden einer Login- oder Logout-Event-PDU an " + s + " nicht moeglich");
                ExceptionHandler.logException(e);
            }
        }
    }

    @Override
    protected void loginRequestAction(ChatPDU receivedPdu) {

        ChatPDU pdu;
        //log.debug("Login-Request-PDU fuer " + receivedPdu.getUserName() + " empfangen");

        // Neuer Client moechte sich einloggen, Client in Client-Liste
        // eintragen
        if (!clients.existsClient(receivedPdu.getUserName())) {
            //log.debug("User nicht in Clientliste: " + receivedPdu.getUserName());
            ClientListEntry client = new ClientListEntry(receivedPdu.getUserName(), connection);
            client.setLoginTime(System.nanoTime());
            clients.createClient(receivedPdu.getUserName(), client);
            clients.changeClientStatus(receivedPdu.getUserName(),
                    ClientConversationStatus.REGISTERING);
            //log.debug("User " + receivedPdu.getUserName() + " nun in Clientliste");

            userName = receivedPdu.getUserName();
            clientThreadName = receivedPdu.getClientThreadName();
            Thread.currentThread().setName(receivedPdu.getUserName());
            //log.debug("Laenge der Clientliste: " + clients.size());
            serverGuiInterface.incrNumberOfLoggedInClients();

            // Login-Event an alle Clients (auch an den gerade aktuell
            // anfragenden) senden
            pdu = ChatPDU.createLoginEventPdu(userName, receivedPdu);
            sendLoginListUpdateEvent(pdu);

            // Liste der betroffenen Clients ermitteln
            Vector<String> sendList = clients.getClientNameList();

            //Waitlist erzeugen
            clients.createWaitList(receivedPdu.getUserName());

            // Event an Clients senden
            for (String s : new Vector<String>(sendList)) {
                client = clients.getClient(s);
                try {
                    if ((client != null)
                            && (client.getStatus() != ClientConversationStatus.UNREGISTERED)) {
                        pdu.setUserName(client.getUserName());
                        client.getConnection().send(pdu);
                        //log.debug("Login-Event-PDU an " + client.getUserName() + " gesendet");
                        clients.incrNumberOfSentChatEvents(client.getUserName());
                        eventCounter.getAndIncrement();
                        //log.debug(userName + ": EventCounter erhoeht = " + eventCounter.get()+ ", Aktueller ConfirmCounter = " + confirmCounter.get()+ ", Anzahl gesendeter ChatMessages von dem Client = "+ receivedPdu.getSequenceNumber());
                    }
                } catch (Exception e) {
                    //log.debug("Senden einer Login-Event-PDU an " + client.getUserName()+ " nicht moeglich");
                    ExceptionHandler.logException(e);
                }
            }

        } else {
            // User bereits angemeldet, Fehlermeldung an Client senden,
            // Fehlercode an Client senden
            pdu = ChatPDU.createLoginErrorResponsePdu(receivedPdu, ChatPDU.LOGIN_ERROR);

            try {
                connection.send(pdu);
                //log.debug("Login-Error-Response-PDU an " + receivedPdu.getUserName()+ " mit Fehlercode " + ChatPDU.LOGIN_ERROR + " gesendet");
            } catch (Exception e) {
                //log.debug("Senden einer Login-Error-Response-PDU an " + receivedPdu.getUserName()+ " nicth moeglich");
                ExceptionHandler.logExceptionAndTerminate(e);
            }
        }
    }

    @Override
    protected void logoutRequestAction(ChatPDU receivedPdu) {

        ChatPDU pdu;
        logoutCounter.getAndIncrement();
        //log.debug("Logout-Request von " + receivedPdu.getUserName() + ", LogoutCount = "+ logoutCounter.get());

        //log.debug("Logout-Request-PDU von " + receivedPdu.getUserName() + " empfangen");

        if (!clients.existsClient(userName)) {
            //log.debug("User nicht in Clientliste: " + receivedPdu.getUserName());
        } else {
            ClientListEntry client = new ClientListEntry(receivedPdu.getUserName(), connection);
            // Event an Client versenden
            pdu = ChatPDU.createLogoutEventPdu(userName, receivedPdu);

            clients.changeClientStatus(receivedPdu.getUserName(),
                    ClientConversationStatus.UNREGISTERING);
            sendLoginListUpdateEvent(pdu);
            serverGuiInterface.decrNumberOfLoggedInClients();

            // Liste der betroffenen Clients ermitteln
            Vector<String> sendList = clients.getClientNameList();

            //Waitlist erzeugen
            clients.createWaitList(receivedPdu.getUserName());

            //Logout Request an Clients versenden
            for (String s : new Vector<String>(sendList)) {
                client = clients.getClient(s);
                try {
                    if ((client != null)
                            && (client.getStatus() != ClientConversationStatus.UNREGISTERED)) {
                        pdu.setUserName(client.getUserName());
                        client.getConnection().send(pdu);
                        //log.debug("Logout-Event-PDU an " + client.getUserName() + " gesendet");
                        clients.incrNumberOfSentChatEvents(client.getUserName());
                        eventCounter.getAndIncrement();
                        //log.debug(userName + ": EventCounter erhoeht = " + eventCounter.get()+ ", Aktueller ConfirmCounter = " + confirmCounter.get()+ ", Anzahl gesendeter ChatMessages von dem Client = "+ receivedPdu.getSequenceNumber());
                    }
                } catch (Exception e) {
                    //log.debug("Senden einer Logout-Event-PDU an " + client.getUserName()+ " nicht moeglich");
                    ExceptionHandler.logException(e);
                }
            }

        }
    }

    @Override
    protected void chatMessageRequestAction(ChatPDU receivedPdu) {

        ClientListEntry client = null;
        clients.setRequestStartTime(receivedPdu.getUserName(), startTime);
        clients.incrNumberOfReceivedChatMessages(receivedPdu.getUserName());
        serverGuiInterface.incrNumberOfRequests();
        //log.debug("Chat-Message-Request-PDU von " + receivedPdu.getUserName()+ " mit Sequenznummer " + receivedPdu.getSequenceNumber() + " empfangen");

        if (!clients.existsClient(receivedPdu.getUserName())) {
            //log.debug("User nicht in Clientliste: " + receivedPdu.getUserName());
        } else {
            // Liste der betroffenen Clients ermitteln
            Vector<String> sendList = clients.getClientNameList();
            ChatPDU pdu = ChatPDU.createChatMessageEventPdu(userName, receivedPdu);

            // Event an Clients senden
            for (String s : new Vector<String>(sendList)) {
                client = clients.getClient(s);
                try {
                    if ((client != null)
                            && (client.getStatus() != ClientConversationStatus.UNREGISTERED)) {
                        pdu.setUserName(client.getUserName());
                        client.getConnection().send(pdu);
                        //log.debug("Chat-Event-PDU an " + client.getUserName() + " gesendet");
                        clients.incrNumberOfSentChatEvents(client.getUserName());
                        eventCounter.getAndIncrement();
                        //log.debug(userName + ": EventCounter erhoeht = " + eventCounter.get()+ ", Aktueller ConfirmCounter = " + confirmCounter.get()+ ", Anzahl gesendeter ChatMessages von dem Client = "+ receivedPdu.getSequenceNumber());
                    }
                } catch (Exception e) {
                    //log.debug("Senden einer Chat-Event-PDU an " + client.getUserName()+ " nicht moeglich");
                    ExceptionHandler.logException(e);
                }
            }

            client = clients.getClient(receivedPdu.getUserName());

            //log.debug("Aktuelle Laenge der Clientliste: " + clients.size());
        }
    }

    /*
    Ankommende Logout Confirm PDU verarbeiten und evtl. Response senden
     */
    private void logoutConfirmAction(ChatPDU receivedPdu){

        //log.debug("Logout Confirm PDU von " + receivedPdu.getEventUserName() + " für User " + receivedPdu.getUserName() + " empfangen.");

        clients.deleteWaitListEntry(receivedPdu.getEventUserName(), receivedPdu.getUserName());

        if(clients.getWaitListSize(receivedPdu.getUserName())==0) {

            clients.changeClientStatus(receivedPdu.getUserName(),
                    ClientConversationStatus.UNREGISTERED);
            // Logout Response senden
            sendLogoutResponse(receivedPdu.getUserName());
            // Worker-Thread des Clients, der den Logout-Request gesendet
            // hat, auch gleich zum Beenden markieren
            clients.finish(receivedPdu.getUserName());
            //log.debug("Laenge der Clientliste beim Vormerken zum Loeschen von "+ receivedPdu.getUserName() + ": " + clients.size());
        }

    }

    /*
    Ankommende Chat Message Confirm PDU verarbeiten und ggf. Response senden
     */

    private void chatMessageConfirmAction(ChatPDU receivedPdu) {

        //log.debug("Chat Message Confirm PDU von " + receivedPdu.getEventUserName() + " für User " + receivedPdu.getUserName() + " empfangen.");
        clients.deleteWaitListEntry(receivedPdu.getEventUserName(),receivedPdu.getUserName());

        if(clients.getWaitListSize(receivedPdu.getUserName())==0){
            ClientListEntry client = new ClientListEntry(receivedPdu.getUserName(), connection);
            //ChatMessageResponse senden
            if (client != null) {
                ChatPDU responsePdu = ChatPDU.createChatMessageResponsePdu(
                        receivedPdu.getUserName(), 0, 0, 0, 0,
                        client.getNumberOfReceivedChatMessages(), receivedPdu.getClientThreadName(),
                        (System.nanoTime() - client.getStartTime()));
                if (responsePdu.getServerTime() / 1000000 > 100) {
                    //log.debug(Thread.currentThread().getName()+ ", Benoetigte Serverzeit vor dem Senden der Response-Nachricht > 100 ms: "+ responsePdu.getServerTime() + " ns = "+ responsePdu.getServerTime() / 1000000 + " ms");
                }
                try {
                    client.getConnection().send(responsePdu);
                    //log.debug("Chat-Message-Response-PDU an " + receivedPdu.getUserName() + " gesendet");
                } catch (Exception e) {
                    //log.debug("Senden einer Chat-Message-Response-PDU an " + client.getUserName()+ " nicht moeglich");
                    ExceptionHandler.logExceptionAndTerminate(e);
                }
            }
        }
    }

    private void loginConfirmAction(ChatPDU receivedPdu) {

        //log.debug("Login Confirm PDU von " + receivedPdu.getEventUserName() + " für User " + receivedPdu.getUserName() + " empfangen.");
        clients.deleteWaitListEntry(receivedPdu.getEventUserName(),receivedPdu.getUserName());

        if(clients.getWaitListSize(receivedPdu.getUserName())==0) {
            // Login Response senden
            ChatPDU responsePdu = ChatPDU.createLoginResponsePdu(userName, receivedPdu);

            try {
                clients.getClient(userName).getConnection().send(responsePdu);
            } catch (Exception e) {
                //log.debug("Senden einer Login-Response-PDU an " + userName + " fehlgeschlagen");
                //log.debug("Exception Message: " + e.getMessage());
            }

            //log.debug("Login-Response-PDU an Client " + userName + " gesendet");

            // Zustand des Clients aendern
            clients.changeClientStatus(userName, ClientConversationStatus.REGISTERED);
        }
    }


    /**
     * Verbindung zu einem Client ordentlich abbauen
     */
    private void closeConnection() {

        //log.debug("Schliessen der Chat-Connection zum " + userName);

        // Bereinigen der Clientliste falls erforderlich

        if (clients.existsClient(userName)) {
            //log.debug("Close Connection fuer " + userName+ ", Laenge der Clientliste vor dem bedingungslosen Loeschen: "+ clients.size());

            clients.deleteClientWithoutCondition(userName);
            //log.debug("Laenge der Clientliste nach dem bedingungslosen Loeschen von " + userName+ ": " + clients.size());
        }

        try {
            connection.close();
        } catch (Exception e) {
            //log.debug("Exception bei close");
            // ExceptionHandler.logException(e);
        }
    }

    /**
     * Antwort-PDU fuer den initiierenden Client aufbauen und senden
     *
     * @param eventInitiatorClient
     *          Name des Clients
     */
    private void sendLogoutResponse(String eventInitiatorClient) {

        ClientListEntry client = clients.getClient(eventInitiatorClient);

        if (client != null) {
            ChatPDU responsePdu = ChatPDU.createLogoutResponsePdu(eventInitiatorClient, 0, 0, 0,
                    0, client.getNumberOfReceivedChatMessages(), clientThreadName);

            //log.debug(eventInitiatorClient + ": SentEvents aus Clientliste: "+ client.getNumberOfSentEvents() + ": ReceivedConfirms aus Clientliste: "+ client.getNumberOfReceivedEventConfirms());
            try {
                clients.getClient(eventInitiatorClient).getConnection().send(responsePdu);
            } catch (Exception e) {
                //log.debug("Senden einer Logout-Response-PDU an " + eventInitiatorClient+ " fehlgeschlagen");
                //log.debug("Exception Message: " + e.getMessage());
            }

            //log.debug("Logout-Response-PDU an Client " + eventInitiatorClient + " gesendet");
        }
    }

    /**
     * Prueft, ob Clients aus der Clientliste geloescht werden koennen
     *
     * @return boolean, true: Client geloescht, false: Client nicht geloescht
     */
    private boolean checkIfClientIsDeletable() {

        ClientListEntry client;

        // Worker-Thread beenden, wenn sein Client schon abgemeldet ist
        if (userName != null) {
            client = clients.getClient(userName);
            if (client != null) {
                if (client.isFinished()) {
                    // Loesche den Client aus der Clientliste
                    // Ein Loeschen ist aber nur zulaessig, wenn der Client
                    // nicht mehr in einer anderen Warteliste ist
                    //log.debug("Laenge der Clientliste vor dem Entfernen von " + userName + ": "+ clients.size());
                    if (clients.deleteClient(userName) == true) {
                        // Jetzt kann auch Worker-Thread beendet werden

                        //log.debug("Laenge der Clientliste nach dem Entfernen von " + userName + ": "+ clients.size());
                        //log.debug("Worker-Thread fuer " + userName + " zum Beenden vorgemerkt");
                        return true;
                    }
                }
            }
        }

        // Garbage Collection in der Clientliste durchfuehren
        Vector<String> deletedClients = clients.gcClientList();
        if (deletedClients.contains(userName)) {
            //log.debug("Ueber Garbage Collector ermittelt: Laufender Worker-Thread fuer "+ userName + " kann beendet werden");
            finished = true;
            return true;
        }
        return false;
    }

    @Override
    protected void handleIncomingMessage() throws Exception {
        if (checkIfClientIsDeletable()) {
            return;
        }

        // Warten auf naechste Nachricht
        ChatPDU receivedPdu = null;

        // Nach einer Minute wird geprueft, ob Client noch eingeloggt ist
        final int RECEIVE_TIMEOUT = 60000;

        try {
            receivedPdu = (ChatPDU) connection.receive(RECEIVE_TIMEOUT);
            // Nachricht empfangen
            // Zeitmessung fuer Serverbearbeitungszeit starten
            startTime = System.nanoTime();

        } catch (ConnectionTimeoutException e) {

            // Wartezeit beim Empfang abgelaufen, pruefen, ob der Client
            // ueberhaupt noch etwas sendet
            //log.debug("Timeout beim Empfangen, " + RECEIVE_TIMEOUT + " ms ohne Nachricht vom Client");

            if (clients.getClient(userName) != null) {
                if (clients.getClient(userName)
                        .getStatus() == ClientConversationStatus.UNREGISTERING) {
                    // Worker-Thread wartet auf eine Nachricht vom Client, aber es
                    // kommt nichts mehr an
                    //log.error("Client ist im Zustand UNREGISTERING und bekommt aber keine Nachricht mehr");
                    // Zur Sicherheit eine Logout-Response-PDU an Client senden und
                    // dann Worker-Thread beenden
                    finished = true;
                }
            }
            return;

        } catch (EndOfFileException e) {
            //log.debug("End of File beim Empfang, vermutlich Verbindungsabbau des Partners");
            finished = true;
            return;

        } catch (java.net.SocketException e) {
            //log.debug("Verbindungsabbruch beim Empfang der naechsten Nachricht vom Client "+ getName());
            finished = true;
            return;

        } catch (Exception e) {
            //log.debug("Empfang einer Nachricht fehlgeschlagen, Workerthread fuer User: " + userName);
            ExceptionHandler.logException(e);
            finished = true;
            return;
        }


        // Empfangene Nachricht bearbeiten
        try {
            switch (receivedPdu.getPduType()) {

                case LOGIN_REQUEST:
                    // Login-Request vom Client empfangen
                    loginRequestAction(receivedPdu);
                    break;

                case CHAT_MESSAGE_REQUEST:
                    // Chat-Nachricht angekommen, an alle verteilen
                    chatMessageRequestAction(receivedPdu);
                    break;

                case LOGOUT_REQUEST:
                    // Logout-Request vom Client empfangen
                    logoutRequestAction(receivedPdu);
                    break;

                case LOGIN_EVENT_CONFIRM:
                    //Login-Confirm angekommen, muss nun in Waitlist vermerkt werden
                    loginConfirmAction(receivedPdu);
                    break;

                case CHAT_MESSAGE_EVENT_CONFIRM:
                    //Chat Message-Confirm angekommen, muss nun in Waitlist vermerkt werden
                    chatMessageConfirmAction(receivedPdu);
                    break;

                case LOGOUT_EVENT_CONFIRM:
                    //Logout-Confirm angekommen, muss nun in Waitlist vermerkt werden
                    logoutConfirmAction(receivedPdu);
                    break;

                default:
                    //log.debug("Falsche PDU empfangen von Client: " + receivedPdu.getUserName()+ ", PduType: " + receivedPdu.getPduType());
                    break;
            }
        } catch (Exception e) {
            //log.error("Exception bei der Nachrichtenverarbeitung");
            ExceptionHandler.logExceptionAndTerminate(e);
        }
    }


}

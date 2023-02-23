package bguspl.set.ex;

import bguspl.set.Env;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * This class manages the dealer's threads and data
 */
public class Dealer implements Runnable {

    /**
     * The game environment object.
     */
    private final Env env;

    /**
     * Game entities.
     */
    private final Table table;
    private final Player[] players;

    /**
     * The list of card ids that are left in the dealer's deck.
     */
    protected final List<Integer> deck;

    /**
     * True iff game should be terminated.
     */
    protected volatile boolean terminate;

    /**
     * The time when the dealer needs to reshuffle the deck due to turn timeout.
     */
    private long reshuffleTime = Long.MAX_VALUE;

    // We added:

    /**
     * object to lock the dealer class.
     */
    public static Object dealerKey;

    /**
     * Queue of players IDs that want the dealer to cheack their sets.
     */
    public LinkedBlockingQueue<Integer> setsCheck;

    /**
     * the amount of time the dealer sleep if not waken in sleepUntilWokenOrTimeout
     * function.
     */
    private long dealerTickingTime;

    /**
     * true iff the time on the clock should appear in red(in accordance to config
     * propeties)
     */
    private boolean warn;

    /**
     * true iff the dealer allow playing
     */
    public boolean allowPlaying;

    /**
     * representing the size of legal set
     */
    public static final int SET_SIZE = 3;

    public Dealer(Env env, Table table, Player[] players) {
        this.env = env;
        this.table = table;
        this.players = players;
        deck = IntStream.range(0, env.config.deckSize).boxed().collect(Collectors.toList());
        setsCheck = new LinkedBlockingQueue<Integer>(env.config.players);
        dealerKey = new Object();
        dealerTickingTime = 1000;
        warn = false;
        allowPlaying = false;
    }

    /**
     * The dealer thread starts here (main loop for the dealer thread).
     */
    @Override
    public void run() {
        env.logger.info("thread " + Thread.currentThread().getName() + " starting.");
        // creating the players threads:
        for (int i = 0; i < players.length; i++) {
            Thread player = new Thread(players[i], "player" + i);
            player.start();
        }
        Collections.shuffle(deck);
        while (!shouldFinish()) {
            placeCardsOnTable();
            allowPlaying = true;
            timerLoop();
            updateTimerDisplay(false);
            removeAllCardsFromTable();
        }
        announceWinners();
        terminate();
        try {
            Thread.sleep(env.config.endGamePauseMillies);
        } catch (InterruptedException ex) {
        }
        env.logger.info("thread " + Thread.currentThread().getName() + " terminated.");
    }

    /**
     * The inner loop of the dealer thread that runs as long as the countdown did
     * not time out.
     */
    private void timerLoop() {
        reshuffleTime = System.currentTimeMillis() + env.config.turnTimeoutMillis + 1000;
        dealerTickingTime = 1000;
        warn = false;
        while (!terminate && System.currentTimeMillis() < reshuffleTime) {
            sleepUntilWokenOrTimeout();
            updateTimerDisplay(false);
            removeCardsFromTable();
            placeCardsOnTable();
            if (!anySetsOnBoard()) {
                break;
            }
        }
    }

    /**
     * Called when the game should be terminated.
     */
    public void terminate() {
        try {
            for (int i = env.config.players - 1; i >= 0; i--) {
                players[i].terminate();
                players[i].playerThread.interrupt();
                players[i].playerThread.join();
            }
            terminate = true;
        } catch (InterruptedException ignored) {
        }
    }

    /**
     * Check if the game should be terminated or the game end conditions are met.
     *
     * @return true iff the game should be finished.
     */
    private boolean shouldFinish() {
        return terminate || env.util.findSets(deck, 1).size() == 0;
    }

    /**
     * Checks cards should be removed from the table and removes them.
     */
    protected void removeCardsFromTable() {
        synchronized (setsCheck) {
            while (!setsCheck.isEmpty()) {
                int playerId = setsCheck.poll();
                // moving the cards marked as tokened to a new simple array:
                int cardsTockendByPlayer[] = new int[SET_SIZE];
                int slotsTockendByPlayer[] = new int[SET_SIZE];
                boolean nullFound = false;
                for (int i = 0; i < SET_SIZE; i++) {
                    if (!players[playerId].slotTokenQ.isEmpty()) {
                        Integer slotPolled = players[playerId].slotTokenQ.poll();
                        slotsTockendByPlayer[i] = slotPolled;
                        cardsTockendByPlayer[i] = table.slotToCard[slotPolled];
                    } else {
                        nullFound = true;
                    }
                }
                // if we found a set:
                if (env.util.testSet(cardsTockendByPlayer) && !nullFound) {
                    players[playerId].point();
                    // restarting the timers:
                    reshuffleTime = System.currentTimeMillis() + env.config.turnTimeoutMillis + 1000;
                    dealerTickingTime = 1000;
                    warn = false;
                    // removing the cards and ui tokens:
                    synchronized (table) {
                        for (int i = 0; i < SET_SIZE; i++) {
                            table.removeCard(slotsTockendByPlayer[i]);
                            // removing the slots from the players token list:
                            for (Player player : players) {
                                player.slotTokenQ.remove(slotsTockendByPlayer[i]);
                            }

                        }
                    }
                }
                // if we didn't find set:
                else if (!env.util.testSet(cardsTockendByPlayer)) {
                    // returning the player token Q and penalty:
                    for (int i = 0; i < SET_SIZE; i++) {
                        players[playerId].slotTokenQ.offer(slotsTockendByPlayer[i]);
                    }
                    players[playerId].penalty();
                }
                synchronized (players[playerId].playerKey) {
                    players[playerId].playerKey.notifyAll(); // Waking the player from the wait
                }
            }
        }
    }

    /**
     * Check if any cards can be removed from the deck and placed on the table.
     * 
     * @post: deckSize = deckSize-openSlots
     */
    protected void placeCardsOnTable() {
        synchronized (table) {
            if (deck.size() != 0 & table.countCards() != env.config.tableSize) {
                List<Integer> openSlots = new ArrayList<Integer>();
                for (int i = 0; i < env.config.tableSize; i++) {
                    if (table.slotToCard[i] == null) {
                        openSlots.add(i);
                    }
                }
                Collections.shuffle(openSlots);
                Collections.shuffle(deck);
                // matching cards to open slots:
                while (!deck.isEmpty() & !openSlots.isEmpty()) {
                    int slotChoosen = openSlots.remove(0);
                    int cardChoosen = deck.remove(0);
                    // update the table
                    table.placeCard(cardChoosen, slotChoosen);
                    // ui update
                    env.ui.placeCard(cardChoosen, slotChoosen);
                }
                if (env.config.hints) {
                    table.hints();
                }
            }

        }

    }

    /**
     * Sleep for a fixed amount of time or until the thread is awakened for some
     * purpose.
     */
    private void sleepUntilWokenOrTimeout() {
        synchronized (setsCheck) {
            try {
                setsCheck.wait(dealerTickingTime);
            } catch (InterruptedException e) {
            }
        }
    }

    /**
     * Reset and/or update the countdown and the countdown display.
     */
    private void updateTimerDisplay(boolean reset) {
        // showing the timer:
        if (reshuffleTime - System.currentTimeMillis() < env.config.turnTimeoutWarningMillis) {
            dealerTickingTime = 10;
            warn = true;
        }
        env.ui.setCountdown(reshuffleTime - System.currentTimeMillis(), warn);
        // showing the freeze time left for the players(show nothing if there is non)
        for (Player player : players) {
            if (player.freezeEndTime - System.currentTimeMillis() > 0) {
                env.ui.setFreeze(player.id, player.freezeEndTime - System.currentTimeMillis());
            }
        }
    }

    /**
     * Returns all the cards from the table to the deck.
     * 
     * @post: no cards in table
     */
    protected void removeAllCardsFromTable() {
        allowPlaying = false;
        ArrayList<Integer> slotsToRemove = new ArrayList<Integer>();
        for (int i = 0; i < env.config.tableSize; i++) {
            slotsToRemove.add(i);
        }
        Collections.shuffle(slotsToRemove);
        synchronized (setsCheck) {
            synchronized (table) {
                for (int slot : slotsToRemove) {
                    // if there is a card in the slot then return it to the deck:
                    if (table.slotToCard[slot] != null) {
                        deck.add(table.slotToCard[slot]);
                    }
                    // remove the card from the choosen slot:
                    table.removeCard(slot);
                    Collections.shuffle(deck);
                }
                // clear the players lists and tokens:
                for (Player player : players) {
                    player.slotTokenQ.clear();
                    synchronized (player.playerKey) {
                        player.playerKey.notifyAll();
                    }
                }
                setsCheck.clear();
            }
        }
    }

    /**
     * Check who is/are the winner/s and displays them.
     */
    protected void announceWinners() {
        int[] winners = new int[env.config.players];
        int numOfEqualScores = 1;
        int maxScore = -1;
        for (int i = 0; i < env.config.players; i++) {
            if (players[i].score() == maxScore) {
                winners[numOfEqualScores] = i;
                numOfEqualScores++;
            }
            if (players[i].score() > maxScore) {
                maxScore = players[i].score();
                numOfEqualScores = 1;
                winners[0] = i;
            }
        }
        int[] endListOfWinners = new int[numOfEqualScores];
        for (int i = 0; i < numOfEqualScores; i++) {
            endListOfWinners[i] = winners[i];
        }
        env.ui.announceWinner(endListOfWinners);
    }

    /*
     * returning true iff there are any sets left on board. terminate if not and
     * the deck is empty.
     */
    protected boolean anySetsOnBoard() {
        List<Integer> cardsOnBoard = new LinkedList<>();
        for (int i = 0; i < env.config.tableSize; i++) {
            if (table.slotToCard[i] != null) {
                cardsOnBoard.add(table.slotToCard[i]);
            }
        }
        if (env.util.findSets(cardsOnBoard, 1).size() == 0) {
            if (deck.isEmpty()) {
                terminate();
            }
            return false;
        }
        return true;
    }
}

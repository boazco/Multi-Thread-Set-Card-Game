package bguspl.set.ex;

import java.util.LinkedList;
import java.util.logging.Level;
import bguspl.set.Env;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;

/**
 * This class manages the players' threads and data
 *
 * @inv id >= 0
 * @inv score >= 0
 */
public class Player implements Runnable {

    /**
     * The game environment object.
     */
    private final Env env;

    /**
     * Game entities.
     */
    private final Table table;

    /**
     * The id of the player (starting from 0).
     */
    public final int id;

    /**
     * The thread representing the current player.
     */
    private Thread playerThread;

    /**
     * The thread of the AI (computer) player (an additional thread used to generate key presses).
     */
    private Thread aiThread;

    /**
     * True iff the player is human (not a computer player).
     */
    private final boolean human;

    /**
     * True iff game should be terminated due to an external event.
     */
    private volatile boolean terminate;

    /**
     * The current score of the player.
     */
    private int score;

    /*
     * Contains the latest 3 key presss
     */
    protected ArrayBlockingQueue<Integer> keyPressedQueue;

    /**
     * Updates everytime the player ask the dealer to check for a set
     */
    protected boolean hadAset = false;

    /**
     * True iff the player submitted a wrong set
     */
    protected boolean shouldBePunished = false;

    /**
     * True iff the last submitted set was checked by the dealer
     */
    protected boolean wasChecked = false;

    /**
     * The dealer
     */
    private Dealer dealer;

    /**
     * The slots where we placed our tokens
     */
    public List<Integer> chosenSlots;


    /**
     * The class constructor.
     *
     * @param env    - the environment object.
     * @param dealer - the dealer object.
     * @param table  - the table object.
     * @param id     - the id of the player.
     * @param human  - true iff the player is a human player (i.e. input is provided manually, via the keyboard).
     */
    public Player(Env env, Dealer dealer, Table table, int id, boolean human) {
        this.env = env;
        this.table = table;
        this.id = id;
        this.human = human;
        this.dealer = dealer;
        this.keyPressedQueue = new ArrayBlockingQueue<Integer>(Table.legalSetSize);
        this.chosenSlots = new LinkedList<Integer>();
        this.terminate = false;
    }

    /**
     * The main player thread of each player starts here (main loop for the player thread).
     */
    @Override
    public void run() {
        playerThread = Thread.currentThread();
        env.logger.log(Level.INFO, "Thread " + Thread.currentThread().getName() + "starting.");
    
        if (!human) {
            createArtificialIntelligence();
        }
    
        while (!terminate) {
            // Check if the player has chosen fewer than 3 slots or if they have already been checked
            if (chosenSlots.size() < Table.legalSetSize || wasChecked) 
                pullingFromKeyPressQ();
    
            // Check if the player has chosen 3 slots and they have not been checked yet
            if (chosenSlots.size() == Table.legalSetSize && !wasChecked)
                submittingSet();
        }
    
        if (!human) {
            try {
                aiThread.join();
            } catch (InterruptedException ignored) {}
        }
        env.logger.log(Level.INFO, "Thread " + Thread.currentThread().getName() + " terminated.");
    }

    private void pullingFromKeyPressQ(){
        synchronized (keyPressedQueue) {
            // Check if there are any keys in the queue
            if (!keyPressedQueue.isEmpty()) {
                // Remove the first key from the queue
                int newSlot = keyPressedQueue.remove();

                // Check if the player has already chosen this slot
                if (chosenSlots.contains(newSlot)) {
                    // If so, remove the token from this slot
                    chosenSlots.remove((Integer) newSlot);
                    env.ui.removeToken(id, newSlot);

                } else if (chosenSlots.size() < Table.legalSetSize) {
                    // Otherwise, check if there is a card in this slot
                    synchronized(table){
                        if (table.slotToCard[newSlot] != null) {
                            try {
                                table.placeToken(id, newSlot, this);
                            } catch (NullPointerException ignored) {}
                            wasChecked = false;
                        }
                    }
  
                }
            }
            keyPressedQueue.notifyAll();
        }
    }

    private void submittingSet(){
        synchronized (keyPressedQueue) {
            synchronized (this) {
                try {
                    table.sem.acquire();
                } catch (InterruptedException e) {}
                table.PlayersWithSet.add(id);
                dealer.dealerThread.interrupt();
                table.sem.release();
                try {
                    this.wait();
                } catch (InterruptedException ignored) {}
            }
            keyPressedQueue.clear();
            keyPressedQueue.notifyAll(); // wake the AI
        }

        // Check if the player had a set
        if (hadAset) {
            point();
        } else if (shouldBePunished) {
            penalty();
        }
        hadAset = false;
        shouldBePunished = false;
        wasChecked = true; 
    }
    

    /**hisd for an AI (computer) player. The main loop of this thread repeatedly generates
     * key presses. If the queue of key presses is full, the thread waits until it is not full.
     */
    private void createArtificialIntelligence() {
        aiThread = new Thread(() -> {
            env.logger.log(Level.INFO, "Thread " + Thread.currentThread().getName() + " starting.");
            while (!terminate) {
                // try {
                //     synchronized (this) { 
                //         wait(100); }
                // } catch (InterruptedException ignored) {}
                int randomPress = (int)(Math.random() * (env.config.tableSize));
                keyPressed(randomPress);
            }
            env.logger.log(Level.INFO, "Thread " + Thread.currentThread().getName() + " terminated.");
        }, "computer-" + id);
        aiThread.start();
    }

    /**
     * Called when the game should be terminated due to an external event.
     */
    public void terminate() {
        terminate = true;
        if(!human)
                aiThread.interrupt();
    }

    /**
     * This method is called when a key is pressed.
     *
     * @param slot - the slot corresponding to the key pressed.
     * @post The keyPressedQueue object will have the slot parameter added to it, unless the queue is already full
     * @inv he size of the keyPressedQueue will not exceed the legalSetSize
     */
    public void keyPressed(int slot) {
        synchronized(keyPressedQueue){
           
            if(!human & keyPressedQueue.size() == Table.legalSetSize){
                try {
                    keyPressedQueue.wait();
                } catch (InterruptedException ignored) {}              
            }

            if(keyPressedQueue.size() < Table.legalSetSize )  
                keyPressedQueue.add(slot);

        } 
    }

    /**
     * Award a point to a player and perform other related actions.
     *
     * @post - the player's score is increased by 1.
     * @post - the player's score is updated in the ui.
     */
    public void point() {
        score++;
        env.ui.setScore(id, score);
        synchronized(this){
            for(long i = env.config.pointFreezeMillis; i > 0; i -= Table.oneSecondsInMillis ){
                env.ui.setFreeze(id, i);
                try { Thread.sleep(Math.min(i, Table.oneSecondsInMillis)); } catch (InterruptedException ignored) {}
            }
            env.ui.setFreeze(id, 0);
            keyPressedQueue.clear();
        }
        int ignored = table.countCards(); // this part is just for demonstration in the unit tests
    }

    /**
     * Penalize a player and perform other related actions.
     */
    public void penalty() {
        synchronized(this){
            for(long i = env.config.penaltyFreezeMillis; i > 0; i -= Table.oneSecondsInMillis ){
                env.ui.setFreeze(id, i);
                try { Thread.sleep(Math.min( Table.oneSecondsInMillis, i)); } catch (InterruptedException ignored) {}
            }
            env.ui.setFreeze(id, 0);
            keyPressedQueue.clear();
        }
    }

    /**
     * Initialize the player thread
     */
    public void initializeThread(){
        playerThread = new Thread(this);
    }

    /**
     * Getter for the players thread
     * @return the players thred
     */
    public Thread getThread(){
        return playerThread;
    }

    /**
     * Returns the player's score
     */
    public int score() {
        return score;
    }

     /**
     * Returns the player's Chosen Slots as an Array
     */
    public Integer[] getChosenSlots(){
        Integer[] slotsArray = new Integer[Table.legalSetSize];
        for(int i = 0; i < chosenSlots.size(); i++)
            slotsArray[i] = chosenSlots.get(i);
        return slotsArray;
    }

}

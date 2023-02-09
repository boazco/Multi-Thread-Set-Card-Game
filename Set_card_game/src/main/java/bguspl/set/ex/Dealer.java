package bguspl.set.ex;

import bguspl.set.Env;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.lang.Math;

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
     * True iff game should be terminated due to an external event.
     */
    protected volatile boolean terminate;

    /**
     * The time when the dealer needs to reshuffle the deck due to turn timeout.
     */
    private long reshuffleTime = Long.MAX_VALUE;

    /**
     * The time when the dealer needs to update the Timer.
     */
    private long nextTime;

    /**
     * The dealer thread.
     */
    protected Thread dealerThread;




    public Dealer(Env env, Table table, Player[] players) {
        this.env = env;
        this.table = table;
        this.players = players;
        this.deck = IntStream.range(0, env.config.deckSize).boxed().collect(Collectors.toList());
    }

    /**
     * The dealer thread starts here (main loop for the dealer thread).
     */
    @Override
    public void run() {
        this.dealerThread = Thread.currentThread();
        env.logger.log(Level.INFO, "Thread " + Thread.currentThread().getName() + " starting.");
        for(int i = 0; i < env.config.players; i++){
            players[i].initializeThread();
            players[i].getThread().start(); 
        }

        while (!shouldFinish()) {
            placeCardsOnTable();
            timerLoop();
            updateTimerDisplay(true);
            removeAllCardsFromTable();
        }
        announceWinners();
        terminate();
        env.logger.log(Level.INFO, "Thread " + Thread.currentThread().getName() + " terminated.");
    }



    /**
     * The inner loop of the dealer thread that runs as long as the countdown did not time out.
     */
    private void timerLoop() {
        if(env.config.hints)
            table.hints();
        env.ui.setCountdown(env.config.turnTimeoutMillis, false);
        nextTime = System.currentTimeMillis() + Table.oneSecondsInMillis;
        reshuffleTime = System.currentTimeMillis() + env.config.turnTimeoutMillis;
        while (!terminate && System.currentTimeMillis() < reshuffleTime) {
            sleepUntilWokenOrTimeout();
            updateTimerDisplay(false);
            placeCardsOnTable();
        }
    }

    /**
     * Called when the game should be terminated due to an external event.
     */
    public void terminate() {
        for(int i = env.config.players-1; i >= 0; i--){    
            try{
                players[i].terminate();
                players[i].getThread().interrupt();
                players[i].getThread().join();
            }catch(InterruptedException ignored){}
        }
        terminate = true;
    }

     /**
     * Check if the game should be terminated or the game end conditions are met.
     *
     * @return true iff the game should be finished.
     */
    protected boolean shouldFinish() {
        return terminate || env.util.findSets(deck, 1).size() == 0;
    }

    /**
     * Check if any cards can be removed from the deck and placed on the table.
     * we removing the Integer from the deck and updatig table and ui.
     */
    protected void placeCardsOnTable() {
        int cardsToAdd = env.config.tableSize - table.countCards();
        if(cardsToAdd > 0) 
            shuffle(deck);
            synchronized(table){
                List<Integer> tableCopy = randomTableSlots();
                int index;
                while(deck.isEmpty() == false & cardsToAdd != 0){
                    Integer card = deck.get(0);
                    for(int i = 0; i < env.config.tableSize; i++){
                        index = tableCopy.get(i);
                        if(table.slotToCard[index] == null){
                            table.placeCard(card, index);
                            deck.remove(card);
                            break;
                        }  
                    }  
                    cardsToAdd--;
                }
            }
    }


    /**
     * Sleep for a fixed amount of time or until the thread is awakened for some purpose.
     */
    private void sleepUntilWokenOrTimeout() {    
         // Continuously check if the timer has expired or if the thread has been interrupted 
        while( nextTime > System.currentTimeMillis()){
             // Check for players with sets
            if(!table.PlayersWithSet.isEmpty())
                checkSetRequests();
            try{ 
                // If the timer is about to expire, the thread don't sleep
                if(reshuffleTime - nextTime < env.config.turnTimeoutWarningMillis){
                    return;
                }
                // Otherwise, sleep until the timer expires or the thread is interrupted
                else
                    Thread.sleep(nextTime - System.currentTimeMillis());
            }catch(IllegalArgumentException ignored) {} catch(InterruptedException ignored){
                // If the thread is interrupted, check for players with sets and continue sleeping
                checkSetRequests();
            }
        }
    }

    /**
     * Reset and/or update the countdown and the countdown display.
     */
    private void updateTimerDisplay(boolean reset) {
        if(!reset){
            if(reshuffleTime - nextTime <= env.config.turnTimeoutWarningMillis)
                env.ui.setCountdown(reshuffleTime - System.currentTimeMillis(), true); 
            else
                env.ui.setCountdown(reshuffleTime - nextTime, false);
            nextTime = nextTime + Table.oneSecondsInMillis;

        }
        else{
            nextTime = System.currentTimeMillis() + Table.oneSecondsInMillis;
            reshuffleTime = System.currentTimeMillis() + env.config.turnTimeoutMillis;
            env.ui.setCountdown(env.config.turnTimeoutMillis, false);
        }

    }
    

    /**
     * Returns all the cards from the table to the deck.
     */
    protected void removeAllCardsFromTable() {

        // Create a copy of the table slots in random order
        List<Integer> tableCopy = randomTableSlots();
        
        synchronized(table){
            // Iterate over the table slots in "random way"
            for(int i = 0; i < env.config.tableSize; i++){
                int index = tableCopy.get(i);
                Integer card = table.slotToCard[tableCopy.get(i)];
                if(card == null)
                    continue;
                try { Thread.sleep(env.config.tableDelayMillis); } catch (InterruptedException ignored) {}

                // Add the card to the deck, remove it from the table, and update the UI
                deck.add(card);
                table.slotToCard[index] = null;
                table.cardToSlot[card] = null;
                env.ui.removeTokens(index);
                env.ui.removeCard(index);


                // Remove the slot from any player's chosen slots
                for(Player player : players)
                    if(player.chosenSlots.contains(index))
                        player.chosenSlots.remove((Integer)index);
            }
        }
    }

    /**
     * Check who is/are the winner/s and displays them.
     * @pre The players array must not be null and must contain at least one element.
     * @post A list of winners will be determined based on the scores of the players in the players array
     * @inv The number of players and their scores will not change
     */
    protected void announceWinners() {
        List<Integer> winners = new ArrayList<>();
        winners.add(players[0].id);
        int topScore = players[0].score();
        for(int i = 1; i < env.config.players; i++){  

            // If the player has a higher score than the current top score, they become the sole winner
            if(players[i].score() > topScore){
                winners.clear();
                topScore = players[i].score();
                winners.add(players[i].id);
            }

            // If the player has the same score as the current top score, they are added to the list of winners
            else if(players[i].score() == topScore)
                winners.add(players[i].id);
        }
        int[] winnersArray = new int[winners.size()];

        // Convert the list of winners to an array and announce the winners
        for(int i = 0; i < winners.size(); i++)
            winnersArray[i] = winners.get(i);
        env.ui.announceWinner(winnersArray);
    }



    private void checkSetRequests() {
        // Get the next player with a set
        int playerId;
        try {
            table.sem.acquire();
        } catch (InterruptedException e) {}
        if (table.PlayersWithSet.isEmpty()){
            table.sem.release();
            return;
        }
        playerId = table.PlayersWithSet.remove();
        table.sem.release();
        
        // Check if the player's chosen slots form a set
        synchronized(players[playerId]) {
            Integer[] chosenSlots = players[playerId].getChosenSlots();
            if (checkSet(chosenSlots, playerId)) {
                // If a set is found, reset the timer and remove the chosen slots from all players who have chosen them
                nextTime = System.currentTimeMillis() + Table.oneSecondsInMillis;
                reshuffleTime = System.currentTimeMillis() + env.config.turnTimeoutMillis;
                env.ui.setCountdown(env.config.turnTimeoutMillis, false);
                for (Player player : players) {
                    for (int slot : chosenSlots) {
                        if (player.chosenSlots.contains(slot)) {
                            player.chosenSlots.remove((Integer)slot);
                        }
                    }
                }
            }
        // Notify the player
        players[playerId].notifyAll();
        }
    }
    

     /**
     * Check if set is legal and handling what follows.
     * @post The cards in the slots specified by the ChosenSlots array will be checked to see if they form a legal set
     * @post If the cards form a legal set, they will be removed from the table and replaced with new cards
     * @post The hadAset and shouldBePunished field of the player specified will be set to T or F depending on whether a legal set was found
     * 
     * @inv The number of cards on the table will remain the same unless a legal set is found and removed
     * , in which case new cards will be added to the table if possible
     * 
     **/
     public boolean checkSet(Integer[] ChosenSlots, int player){
       //checking if all cards are still on the table or the request sent before we remove card
       for(int i = 0; i < ChosenSlots.length; i++){
            if(ChosenSlots[i] == null || table.getCardOfSlot(ChosenSlots[i]) == null){
                players[player].hadAset = false;
                return false;
                //no penalty needed
            }
        }

       int[] chosenCards = new int[Table.legalSetSize];
       for(int i = 0; i < ChosenSlots.length; i++){
            chosenCards[i] = table.slotToCard[ChosenSlots[i]];
        }

        if(!env.util.testSet(chosenCards)){
            players[player].hadAset = false;
            players[player].shouldBePunished = true;
            return false;
        }

        for(int slot :ChosenSlots)
            table.removeCard(slot);
        placeCardsOnTable();
        players[player].hadAset = true;
        players[player].shouldBePunished = false;  
        return true; 
     }


     /**
      * Randoming the removig/placing card order
      * @param list
      */
      private List<Integer> randomTableSlots(){
        List<Integer> tableCopy = new ArrayList<>();
        for(int i = 0; i < env.config.tableSize; i++)
            tableCopy.add(i);
        shuffle(tableCopy);
        return tableCopy;
      }


    /**
     * Shuffle the deck
     * @pre The input list must not be null
     * @post The elements of the input list will be shuffled randomly
     * @inv All elements of the list will still be present in the list after the shuffle
     */
    protected void shuffle(List<Integer> list){
        for(int i = 0; i < list.size(); i++){
            int index = (int) (Math.random() * (list.size() - 1));
            Integer temp = list.get(index);
            list.set(index, list.get(i));
            list.set(i, temp);
        }
    }
}

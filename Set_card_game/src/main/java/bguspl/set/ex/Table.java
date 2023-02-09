package bguspl.set.ex;

import bguspl.set.Env;
import bguspl.set.UserInterface;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Semaphore;


/**
 * This class contains the data that is visible to the player.
 *
 * @inv slotToCard[x] == y iff cardToSlot[y] == x
 */
public class Table extends NullPointerException {

    /**
     * The game environment object.
     */
    private final Env env;

    /**
     * Mapping between a slot and the card placed in it (null if none).
     */
    protected final Integer[] slotToCard; // card per slot (if any)

    /**
     * Mapping between a card and the slot it is in (null if none).
     */
    protected final Integer[] cardToSlot; // slot per card (if any)

    /** */
    protected ArrayBlockingQueue<Integer> PlayersWithSet;

    protected Semaphore  sem;

    /**
     * To avoid using magic numbers. 
     */
    protected static final int legalSetSize = 3;
    protected static final int oneSecondsInMillis = 1000;

    /**
     * Constructor for testing.
     *
     * @param env        - the game environment objects.
     * @param slotToCard - mapping between a slot and the card placed in it (null if none).
     * @param cardToSlot - mapping between a card and the slot it is in (null if none).
     */
    public Table(Env env, Integer[] slotToCard, Integer[] cardToSlot) {

        this.env = env;
        this.slotToCard = slotToCard;
        this.cardToSlot = cardToSlot;
        this.PlayersWithSet = new ArrayBlockingQueue<Integer>(env.config.players);
        this.sem = new Semaphore(1, true);
    }

    /**
     * Constructor for actual usage.
     *
     * @param env - the game environment objects.
     */
    public Table(Env env) {

        this(env, new Integer[env.config.tableSize], new Integer[env.config.deckSize]);
    }

    /**
     * This method prints all possible legal sets of cards that are currently on the table.
     */
    public void hints() {
        List<Integer> deck = Arrays.stream(slotToCard).filter(Objects::nonNull).collect(Collectors.toList());
        env.util.findSets(deck, Integer.MAX_VALUE).forEach(set -> {
            StringBuilder sb = new StringBuilder().append("Hint: Set found: ");
            List<Integer> slots = Arrays.stream(set).mapToObj(card -> cardToSlot[card]).sorted().collect(Collectors.toList());
            int[][] features = env.util.cardsToFeatures(set);
            System.out.println(sb.append("slots: ").append(slots).append(" features: ").append(Arrays.deepToString(features)));
        });
    }

    /**
     * Count the number of cards currently on the table.
     *
     * @return - the number of cards on the table.
     */
    public int countCards() {
        int cards = 0;
        for (Integer card : slotToCard)
            if (card != null)
                ++cards;
        return cards;
    }

    /**
     * Places a card on the table in a grid slot.
     * @param card - the card id to place in the slot.
     * @param slot - the slot in which the card should be placed.
     *
     * @post - the card placed is on the table, in the assigned slot.
     * @inv The size of the cardToSlot and slotToCard arrays will remain unchanged
     */
    public void placeCard(int card, int slot) {
        try { Thread.sleep(env.config.tableDelayMillis); } catch (InterruptedException ignored) {}
        cardToSlot[card] = slot;
        slotToCard[slot] = card;
        env.ui.placeCard(card, slot); 
    }

    /**
     * Removes a card from a grid slot on the table.
     * @param slot - the slot from which to remove the card.
     * @post The card in the specified slot will be removed from the slotToCard and cardToSlot arrays
     * @inv The size of the slotToCard and cardToSlot arrays will remain unchanged
     */
    public void removeCard(int slot) {
        try {
            Thread.sleep(env.config.tableDelayMillis);
        } catch (InterruptedException ignored) {}
        cardToSlot[slotToCard[slot]] = null;
        slotToCard[slot] = null; 
        env.ui.removeTokens(slot);
        env.ui.removeCard(slot); 
    }

    /**
     * Places a player token on a grid slot.
     * @param player - the player the token belongs to.
     * @param slot   - the slot on which to place the token.
     * @post The specified slot will have a player token placed on it.
     * @post The slot parameter will be added to the chosenSlots list of the thePlayer object
     */
    public void placeToken(int player, int slot, Player thePlayer) throws NullPointerException{
        if(slotToCard[slot] == null)
            throw new NullPointerException("tried to place token on empty slot");
        thePlayer.chosenSlots.add(slot);
        env.ui.placeToken(player, slot);
    }

    /**
     * Removes a token of a player from a grid slot.
     * @param player - the player the token belongs to.
     * @param slot   - the slot from which to remove the token.
     * @return       - true iff a token was successfully removed.
     */
    public boolean removeToken(int player, int slot) {
        //we need to synchronized it so we will return true only if the reomve token was succes.
        env.ui.removeToken(player, slot);
        return false;
    }

    /**
     * card to slot getter
     */
    public Integer getSlotOfCard(int card){
        return cardToSlot[card];
    }

    /**
     * slot to card getter
     */
    public Integer getCardOfSlot(int slot){
        return slotToCard[slot];
    }

}

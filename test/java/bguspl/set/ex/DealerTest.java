package bguspl.set.ex;

import bguspl.set.Config;
import bguspl.set.Env;
import bguspl.set.UserInterface;
import bguspl.set.Util;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class DealerTest {

    Dealer dealer;
    @Mock
    Util util;
    @Mock
    private UserInterface ui;
    @Mock
    private Table table;
    @Mock
    private Logger logger;

    @BeforeEach
    void setUp() {
        // purposely do not find the configuration files (use defaults here).
        Env env = new Env(logger, new Config(logger, (String) null), ui, util);
        Player[] players = { new Player(env, dealer, table, 0, false), new Player(env, dealer, table, 1, false) };
        table = new Table(env);
        dealer = new Dealer(env, table, players);
    }

    @AfterEach
    void tearDown() {
    }

    @Test
    void placeCardsTest() {
        // checks if after placing cards on an epmty table the number of cards in the
        // deck reduce in table size
        assertEquals(81, dealer.deck.size());
        dealer.placeCardsOnTable();
        assertEquals(69, dealer.deck.size());
    }

    @Test
    void RemoveAllCardsTest() {
        // we want to check if after we remove all cards back to the deck the deck size
        // is right

        assertEquals(81, dealer.deck.size());

        dealer.placeCardsOnTable();// first we need to have cards on the table

        assertEquals(69, dealer.deck.size());

        dealer.removeAllCardsFromTable();

        assertEquals(81, dealer.deck.size());
    }

}

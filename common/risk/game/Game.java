package risk.game;

import java.awt.Color;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

import risk.Risk;
import risk.lib.Button;
import risk.lib.DiceTexture;
import risk.lib.Drawable;
import risk.lib.Input;
import risk.lib.RiskCanvas;
import risk.lib.ThreadLocks;

/**
 * Represents the main game logic and loops
 * 
 * @author Gabriel Ip
 * @author Sean Purcell
 * @author Miranda Zhou
 * 
 */
public class Game {

	private final int UPDATE_THREAD_ID = 1;
	
	// This offset will be added to the "source" part of a message to determine
	// the number for the lock
	private final int INPUT_ID_OFFSET = 0x100;

	private final int UPDATE_RATE = 32;
	
	private RiskCanvas r;
	private Input i;

	private Map map;

	/**
	 * Represents the current mode that the game is in<br>
	 * 1. Game setup mode<br>
	 * 2. Main game mode<br>
	 */
	private int mode;

	/**
	 * Represents the current part of setup<br>
	 * 1. Choose number of players<br>
	 * 2. Choose colours<br>
	 * 3. Roll dice<br>
	 * 4. Choose territories<br>
	 * 5. Deploy reinforcements
	 */
	private int setupMode;
	
	/**
	 * Current part of the game<br>
	 * 1. Deploy begin turn reinforcements<br>
	 * 2. Normal game mode<br>
	 * 3. Battle mode<br>
	 */
	private int gameMode;

	private List<Button> numberButtons;
	private List<Button> colourButtons;
	private int[] dice;
	private int[] diceTimers;

	private int diceSwitchTimer;
	private int diceDisplayCountdown;
	private boolean[] firstTurnContenders;

	private int numTerritoriesClaimed;
	
	private int numSetupTroops;
	
	private int numPlayers;

	private int turn;

	private List<Army> armies;

	/**
	 * Applicable during attack and reinforce.  
	 * Represents the currently selectedCountry army.
	 */
	private Country selectedCountry;
	
	private Country attackTarget;
	
	/**
	 * Set to false if the game should exit
	 */
	private boolean running;

	/**
	 * Represents the current speed of the game in frames per second
	 */
	private int fps;

	/**
	 * Represents desired fps
	 */
	private int fpsDesired = 60;

	public Game() {
		map = new Map();
		i = new Input(this);
		r = new RiskCanvas(this, i);
		this.armies = new ArrayList<Army>();
		mode = 1;
		running = true;
	}

	// MAIN GAME LOOP AND RELATED MISC

	/**
	 * Begins the main game loop.
	 */
	public void run() {
		long lastTime = System.currentTimeMillis();
		while (isRunning()) {
			if (r.hasFocus()) { // Ensures that the game does not render when it
								// is not in focus

				// Calculate time since last update
				long time = System.currentTimeMillis();
				int delta = (int) (time - lastTime);
				lastTime = time;
				try {
					setFps(1000 / delta);
				} catch (ArithmeticException e) {
				}
				
				// Request lock on GAME_STATE lock to update and render
				ThreadLocks.requestLock(ThreadLocks.GAME_STATE,UPDATE_THREAD_ID);
				// Runs the update method with the given delta
				this.update(delta);
				
				// Create the offscreen buffer containing the frame to be rendered
				r.createFrame();
				// Release lock now that we're done with it 
				ThreadLocks.releaseLock(ThreadLocks.GAME_STATE,UPDATE_THREAD_ID);
				
				// Renders the game
				r.repaint();
				// Limits the game to 30 fps
				while (System.currentTimeMillis() - time <= UPDATE_RATE)
					;
			}
		}
	}

	private void update(int delta) {
		switch (mode) {
		case 1:
			updateSetupMode(delta);
			break;
		case 2:
			updateGameMode(delta);
			break;
		}
	}

	private void updateSetupMode(int delta) {
		switch (setupMode) {
		case 0:
			enterSetupMode();
			break;
		case 3:
			updateSetupDice(delta);
			break;
		}
	}
	
	private void updateGameMode(int delta){
		switch(gameMode){
		case 0: gameMode = 1;
		case 1:
			break;
		}
	}

	private void enterSetupMode() {
		setupMode = 1;
		initSetupButtons();
		armies = new ArrayList<Army>();
	}
	
	private void enterNextTurn(){
		incrementTurn();
		currentArmy().setFreeUnits(calculateReinforcements());
	}
	
	private int calculateReinforcements(){
		int reinforcements = 0;
		reinforcements += Math.max(3, currentArmy().getUnits().size()/3);
		reinforcements += currentArmy().calculateContinentBonus();
		
		return reinforcements;
	}
	
	private void updateSetupDice(int delta) {
		if (diceDisplayCountdown > 0) {
			diceDisplayDone(delta);
		} else {
			diceDisplayUpdate(delta);
		}
	}
	
	private void diceDisplayDone(int delta){
		diceDisplayCountdown -= delta;
		if (diceDisplayCountdown <= 0) {
			int max = 0;
			for (int i = 0; i < numPlayers; i++) {
				if (dice[i] > max) {
					max = dice[i];
				}
			}
			int first = -1;
			for(int i = 0; i < numPlayers; i++){
				if(dice[i] != max || !firstTurnContenders[i]){
					firstTurnContenders[i] = false;
				}else{
					diceTimers[i] = Risk.r.nextInt(2000) + 1500;
					if(first == -1){ //No one else has won yet
						first = i; //Indicates that that player won the dice roll
					}else{
						first = -2; //Indicates that there is more than one army with the max dice number
					}
				}
			}
			if(first >= 0){
				enterTerritoryAllocateMode(first);
			}
		}
	}
	
	private void diceDisplayUpdate(int delta){
		for (int i = 0; i < diceTimers.length; i++) {
			diceTimers[i] -= delta;
		}
		diceSwitchTimer -= delta;
		if (diceSwitchTimer <= 0) {
			diceSwitchTimer += 83; // 12 switches per second approximately

			boolean diceDone = true;
			for (int i = 0; i < dice.length; i++) {
				if (diceTimers[i] > 0 && firstTurnContenders[i]) {
					dice[i] = Risk.r.nextInt(6) + 1;
					diceDone = false;
				}
			}
			if (diceDone) {
				diceDisplayCountdown = 1000;
			}
		}
	}

	private void enterTerritoryAllocateMode(int first){
		int offset = first;
		
		//Set the first army in the list to be the one designated to go first
		Risk.rotateList(armies, offset);
		
		setupMode = 4;
		turn = 0;
		
		int startingTroops = (10 - numPlayers) * 5;
		for(Army a : armies){
			a.setFreeUnits(startingTroops);
		}
	}
	
	private void enterSetupReinforcement(){
		setupMode = 5;
		turn = 0;
		
		numSetupTroops = 3;
	}
	
	private void enterAttack(Country c){
		this.gameMode = 3;
		attackTarget = c;
	}
	
	private void initSetupButtons() {
		List<BufferedImage> numberButtonTextures = generateNumberButtonTextures(r);
		numberButtons = new ArrayList<Button>();
		for (int i = 0; i < numberButtonTextures.size(); i++) {
			numberButtons.add(new Button(50 + 55 * i, 650, numberButtonTextures
					.get(i), i + 3));
		}
		System.out.println("number buttons initialized");
		List<BufferedImage> colourButtonTextures = generateColourButtonTextures();
		colourButtons = new ArrayList<Button>();
		for (int i = 0; i < colourButtonTextures.size(); i++) {
			colourButtons.add(new Button(50 + 55 * i, 650, colourButtonTextures
					.get(i), i));
		}
	}

	private void enterGamePhase(){
		mode = 2;
		setupMode = 0;
		
		turn = -1;
		
		enterNextTurn();
	}
	
	public void draw(Graphics2D g) {
		drawMap(g);
		drawUnits(g);
		switch (mode) {
		case 1:
			drawSetupMode(g);
			break;
		case 2: 
			drawGameMode(g);
			break;
		}
	}

	public void drawUnits(Graphics2D g) {
		for(Country c : map.getCountries()){
			if(c != null && c.getUnit() != null){
				c.getUnit().drawSelf(g);
			}
		}
	}

	private void drawSetupMode(Graphics2D g) {
		switch (setupMode) {
		case 1:
			drawString(g, "Number of players: ", 30, 25, 645, Color.BLACK);
			break;
		case 2:
			drawString(g, "Player " + (turn + 1), 40, 25, 625, Color.BLACK);
			drawString(g, "Choose a colour", 30, 30, 645,
					Color.BLACK);
			break;
		case 3:
			drawDice(g);
			break;
		case 4:
			drawTurn(g);
			drawClaimTerritories(g);
			break;
		case 5:
			drawTurn(g);
			drawDeploySetupTroops(g);
			break;
		}

		drawButtons(g);
	}

	private void drawGameMode(Graphics2D g){
		drawTurn(g);
		switch(gameMode){
		case 1: 
			drawReinforcements(g);
			break;
		case 2:
			drawSelectedCountry(g);
		}
	}
	
	private void drawTurn(Graphics2D g){
		drawString(g, currentArmy().getName(),40,25,625,armies.get(turn).getColour());
	}
	
	private void drawDice(Graphics2D g) {
		for (int i = 0; i < numPlayers; i++) {
			if(firstTurnContenders[i]){
				drawString(g, "Player " + (i + 1),25,
						20 + 100 * i,645, armies.get(i).getColour());
				g.drawImage(DiceTexture.getDieTexture(dice[i]), 30 + 100 * i, 660,
						null);
			}
		}
	}
	
	private void drawClaimTerritories(Graphics2D g){
		drawString(g, "Claim an unclaimed territory.", 30, 30, 645, Color.BLACK);
	}

	private void drawDeploySetupTroops(Graphics2D g){
		drawString(g, "Deploy " + numSetupTroops + " troops.", 30, 30, 645, Color.BLACK);
		drawString(g, "Troops left: " + currentArmy().getFreeUnits(), 30, 30, 665, Color.BLACK);
	}
	
	private void drawMap(Graphics2D g) {
		try {
			g.drawImage(this.getMap().getTexture(), 0, 0, null);
		} catch (NullPointerException e) {
			System.out.println("map not found");
		}
	}

	private void drawReinforcements(Graphics2D g) {
		Army a = this.currentArmy();
		this.drawString(g, "Free Troops: " + a.getFreeUnits(), 30, 30, 645, Color.BLACK);
	}

	private void drawSelectedCountry(Graphics2D g){
		if(selectedCountry == null){
			return;
		}
		
		
	}
	
	private void drawConnections(Graphics2D g) {
		g.setColor(Color.BLACK);
		List<Country> countries = this.getMap().getCountries();
		for (int i = 1; i < countries.size(); i++) {
			Country c = countries.get(i);
			List<Country> connections = c.getConnections();
			// System.out.println(c);
			// System.out.println(connections);
			for (Country conn : connections) {
				if (conn.getId() > c.getId() && conn.getX() != 0) {
					// System.out.println(c + " <-> " + conn);
					g.drawLine(c.getX(), c.getY(), conn.getX(), conn.getY());
				}
			}
		}
	}

	public void drawButtons(Graphics2D g) {
		if (this.getButtonList() != null) {
			for (Button b : this.getButtonList()) {
				draw(b, g);
			}
		}
	}

	public void drawString(Graphics2D g, String str, int fontSize, int x,
			int y, Color c) {
		g.setFont(g.getFont().deriveFont((float) fontSize));
		g.setColor(c);
		g.drawString(str, x, y);
	}
	
	private void addUnit(int troops, Army a, Country c){
		 //Due to the many pointers that must be consistent, this is the only method that should be used for creating units
		Unit u = new Unit(troops, a, c);
		a.getUnits().add(u);
		c.setUnit(u);
	}
	
	// INPUT HANDLING
	public void countryClicked(Country c, int x, int y) {
		switch(mode){
		case 1:
			countryClickedSetupMode(c);
			break;
		case 2:
			countryClickedGameMode(c);
			break;
		}
	}
	
	private void countryClickedSetupMode(Country c){
		switch(setupMode){
		case 4:
			if(c.getUnit() == null){
				this.addUnit(1,currentArmy(), c);
				incrementTurn();
				numTerritoriesClaimed++;
				currentArmy().setFreeUnits(currentArmy().getFreeUnits() - 1);
				if(numTerritoriesClaimed == 42){
					enterSetupReinforcement();
				}
			}
			break;
		case 5:
			if(currentArmy() == c.getUnit().getArmy()){
				addTroop(c);
				numSetupTroops--;
				if(this.turn == numPlayers - 1 && currentArmy().getFreeUnits() == 0){
					enterGamePhase();
					break;
				}
				if(numSetupTroops == 0){
					incrementTurn();
					numSetupTroops = Math.min(3, currentArmy().getFreeUnits());
				}
			}
			break;
		}
	}

	private void countryClickedGameMode(Country c){
		switch(gameMode){
		case 1:
			if(currentArmy() == c.getUnit().getArmy()){
				this.addTroop(c);
				if(currentArmy().getFreeUnits() == 0){
					gameMode++;
				}
			}
			break;
		case 2:
			if(currentArmy() == c.getUnit().getArmy()){
				selectedCountry = c;
			}else if(selectedCountry != null){
				if(selectedCountry.getConnections().contains(c)){
					enterAttack(c);
				}
			}
		}
	}
	
	public void buttonClicked(Button b, int x, int y) {
		switch (mode) {
		case 1:
			switch (setupMode) {
			case 1:
				numPlayers = b.getId();
				setupMode = 2; // Enter choose colour mode
				turn = 0;
				break;
			case 2:
				colourPicked(b);
			}
		}
	}

	private void colourPicked(Button b) {
		colourButtons.remove(b);
		armies.add(new Army(b.getId(),this));
		turn++;
		System.out.println(numPlayers);
		if (turn == numPlayers) {
			setupMode = 3;
			dice = new int[numPlayers];
			diceTimers = new int[numPlayers];
			
			firstTurnContenders = new boolean[numPlayers];
			
			for (int i = 0; i < numPlayers; i++) {
				diceTimers[i] = Risk.r.nextInt(2000) + 1500;
				firstTurnContenders[i] = true;
			}

		}
	}

	private void addTroop(Country c){
		c.getUnit().incrementTroops();
		c.getUnit().getArmy().setFreeUnits(c.getUnit().getArmy().getFreeUnits() - 1);

	}
	
	private void nullClicked(){
		switch(mode){
		case 2:
			switch(gameMode){
			case 2: selectedCountry = null;
			}
		}
	}
	
	public static void draw(Drawable d, Graphics g) {
		if (d.getTexture() != null) {
			g.drawImage(d.getTexture(), d.getX(), d.getY(), null);
		}
	}

	public static void drawDie(Graphics2D g, int x, int y, int val) {
		g.drawImage(DiceTexture.getDieTexture(val), x, y, null);
	}

	public List<BufferedImage> generateNumberButtonTextures(
			RiskCanvas riskCanvas) {
		final int width = 50;
		final int height = 50;

		List<BufferedImage> textures = new ArrayList<BufferedImage>();
		BufferedImage base = new BufferedImage(width, height,
				BufferedImage.TYPE_INT_ARGB);
		Graphics baseG = base.getGraphics();
		baseG.setColor(Color.BLACK);
		baseG.fillRoundRect(0, 0, width, height, 10, 10);
		for (char i = '3'; i <= '6'; i++) {
			BufferedImage clone = Risk.cloneImage(base);
			Graphics2D g = (Graphics2D) clone.getGraphics();
			g.setColor(Color.DARK_GRAY);
			g.fillRoundRect(5, 5, 40, 40, 10, 10);
			g.setFont(riskCanvas.army.deriveFont(50f));
			g.setColor(Color.WHITE);
			FontMetrics fm = g.getFontMetrics();
			g.drawString(Character.toString(i),
					width / 2 - fm.charWidth(i) / 2,
					height / 2 + fm.getHeight() / 3);
			textures.add(clone);
			System.out.println(i + "buttonmade");
		}

		return textures;
	}

	public List<BufferedImage> generateColourButtonTextures() {
		final int width = 50;
		final int height = 50;

		List<BufferedImage> textures = new ArrayList<BufferedImage>();
		BufferedImage base = new BufferedImage(width, height,
				BufferedImage.TYPE_INT_ARGB);
		Graphics baseG = base.getGraphics();
		baseG.setColor(Color.black);
		baseG.fillRoundRect(0, 0, width, height, 10, 10);
		for (int i = 0; i < 6; i++) {
			BufferedImage clone = Risk.cloneImage(base);
			Graphics2D g = (Graphics2D) clone.getGraphics();
			g.setColor(Army.getColorByType(i));
			g.fillRoundRect(5, 5, 40, 40, 10, 10);

			textures.add(clone);
		}
		return textures;
	}

	/**
	 * All input should go through this, to make it easier to combine local multiplayer, non-local multiplayer, and AI
	 * @param message
	 * 		The message.  The format of the message is outlined in MessageProtocol.txt
	 * @param source
	 */
	public void message(String message, int source){
		
		// Request the GAME_STATE lock to avoid concurrency issues 
		ThreadLocks.requestLock(ThreadLocks.GAME_STATE, source + INPUT_ID_OFFSET);
		int t = message.charAt(0);
		switch(t){
		//hexadecimal used because it seemed fitting
		case 0x1: parseButtonMessage(message.substring(1),source); break;
		case 0x2: parseCountryMessage(message.substring(1),source); break;
		case 0x3: nullClicked();
		}
		
		ThreadLocks.releaseLock(ThreadLocks.GAME_STATE, source + INPUT_ID_OFFSET);
	}
	
	private void parseButtonMessage(String str,int source){
		int i = str.charAt(0);
		Button clicked = null;
		for(Button b : getButtonList()){
			if(b.getId() == i){
				clicked = b;
				break;
			}
		}
		buttonClicked(clicked,0,0);
	}
	
	private void parseCountryMessage(String str,int source){
		int i = Integer.parseInt(str.substring(0,2));
		countryClicked(map.getCountryById(i),0,0);
	}
	
	private void incrementTurn(){
		turn++;
		turn%=numPlayers;
	}
	
	// SETTERS AND GETTERS
	public List<Button> getButtonList() {
		switch (mode) {
		case 1:
			switch (setupMode) {
			case 1:
				return numberButtons;
			case 2:
				return colourButtons;
			}
			break;
		case 2: 
			switch(gameMode){
			case 1:
			case 2:
			case 3:
				break;
			}
			break;
		}
		return null;
	}

	public Map getMap() {
		return map;
	}

	public void setMap(Map map) {
		this.map = map;
	}

	public int getFps() {
		return fps;
	}

	public void setFps(int fps) {
		this.fps = fps;
	}

	public List<Army> getArmies() {
		return armies;
	}
	
	public Army currentArmy(){
		return armies.get(turn);
	}

	public void setArmies(List<Army> armies) {
		this.armies = armies;
	}

	public int getTurn() {
		return turn;
	}

	public void setTurn(int turn) {
		this.turn = turn;
	}

	public boolean isRunning() {
		return running;
	}
}

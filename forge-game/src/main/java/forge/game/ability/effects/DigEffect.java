package forge.game.ability.effects;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import forge.card.MagicColor;
import forge.game.Game;
import forge.game.GameActionUtil;
import forge.game.GameEntityCounterTable;
import forge.game.ability.AbilityKey;
import forge.game.ability.AbilityUtils;
import forge.game.ability.SpellAbilityEffect;
import forge.game.card.*;
import forge.game.event.GameEventCombatChanged;
import forge.game.player.DelayedReveal;
import forge.game.player.Player;
import forge.game.player.PlayerView;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.TargetRestrictions;
import forge.game.zone.PlayerZone;
import forge.game.zone.ZoneType;
import forge.util.CardTranslation;
import forge.util.Lang;
import forge.util.Localizer;
import forge.util.TextUtil;
import forge.util.collect.FCollectionView;

public class DigEffect extends SpellAbilityEffect {

    @Override
    protected String getStackDescription(SpellAbility sa) {
        final Card host = sa.getHostCard();
        final StringBuilder sb = new StringBuilder();
        final int numToDig = AbilityUtils.calculateAmount(host, sa.getParam("DigNum"), sa);
        final int numToChange = (sa.hasParam("ChangeNum") ?
                AbilityUtils.calculateAmount(host, sa.getParam("ChangeNum"), sa) : 1);
        final List<Player> tgtPlayers = getTargetPlayers(sa);

        String verb = " looks at ";
        if (sa.hasParam("Reveal") && sa.getParam("Reveal").equals("True")) {
            verb = " reveals ";
        } else if (sa.hasParam("DestinationZone") && sa.getParam("DestinationZone").equals("Exile") &&
                numToDig == numToChange) {
            verb = " exiles ";
        }
        sb.append(host.getController()).append(verb).append("the top ");
        sb.append(numToDig == 1 ? "card" : (Lang.getNumeral(numToDig) + " cards")).append(" of ");

        if (tgtPlayers.contains(host.getController())) {
            sb.append("their ");
        } else {
            for (final Player p : tgtPlayers) {
                sb.append(Lang.getInstance().getPossesive(p.getName())).append(" ");
            }
        }
        sb.append("library.");

        if (numToDig != numToChange) {
            String destZone1 = sa.hasParam("DestinationZone") ?
                    sa.getParam("DestinationZone").toLowerCase() : "hand";
            String destZone2 = sa.hasParam("DestinationZone2") ?
                    sa.getParam("DestinationZone2").toLowerCase() : "on the bottom of their library in any order.";
            if (sa.hasParam("RestRandomOrder")) {
                destZone2 = destZone2.replace("any", "a random");
            }

            String verb2 = "put ";
            String where = "in their hand ";
            if (destZone1.equals("exile")) {
                verb2 = "exile ";
                where = "";
            }
            sb.append(" They ").append(verb2).append(Lang.getNumeral(numToChange)).append(" of them ").append(where);
            sb.append(sa.hasParam("ExileFaceDown") ? "face down " : "").append("and put the rest ");
            sb.append(destZone2);
        }

        return sb.toString();
    }

    @Override
    public void resolve(SpellAbility sa) {
        final Card host = sa.getHostCard();
        final Player player = sa.getActivatingPlayer();
        final Game game = player.getGame();
        final Player cont = host.getController();
        Player chooser = player;
        int numToDig = AbilityUtils.calculateAmount(host, sa.getParam("DigNum"), sa);

        final ZoneType srcZone = sa.hasParam("SourceZone") ? ZoneType.smartValueOf(sa.getParam("SourceZone")) : ZoneType.Library;

        final ZoneType destZone1 = sa.hasParam("DestinationZone") ? ZoneType.smartValueOf(sa.getParam("DestinationZone")) : ZoneType.Hand;
        final ZoneType destZone2 = sa.hasParam("DestinationZone2") ? ZoneType.smartValueOf(sa.getParam("DestinationZone2")) : ZoneType.Library;

        int libraryPosition = sa.hasParam("LibraryPosition") ? Integer.parseInt(sa.getParam("LibraryPosition")) : -1;
        int destZone1ChangeNum = 1;
        final boolean mitosis = sa.hasParam("Mitosis");
        String changeValid = sa.getParamOrDefault("ChangeValid", "");
        final boolean anyNumber = sa.hasParam("AnyNumber");

        final int libraryPosition2 = sa.hasParam("LibraryPosition2") ? Integer.parseInt(sa.getParam("LibraryPosition2")) : -1;
        final boolean optional = sa.hasParam("Optional");
        final boolean noMove = sa.hasParam("NoMove");
        final boolean skipReorder = sa.hasParam("SkipReorder");

        // A hack for cards like Explorer's Scope that need to ensure that a card is revealed to the player activating the ability
        final boolean forceRevealToController = sa.hasParam("ForceRevealToController");

        // These parameters are used to indicate that a dialog box must be show to the player asking if the player wants to proceed
        // with an optional ability, otherwise the optional ability is skipped.
        final boolean mayBeSkipped = sa.hasParam("PromptToSkipOptionalAbility");
        final String optionalAbilityPrompt = sa.getParamOrDefault("OptionalAbilityPrompt", "");

        boolean remZone1 = false;
        boolean remZone2 = false;
        if (sa.hasParam("RememberChanged")) {
            remZone1 = true;
        }
        if (sa.hasParam("RememberMovedToZone")) {
            if (sa.getParam("RememberMovedToZone").contains("1")) {
                remZone1 = true;
            }
            if (sa.getParam("RememberMovedToZone").contains("2")) {
                remZone2 = true;
            }
        }

        boolean changeAll = false;
        boolean allButOne = false;
        boolean totalCMC = sa.hasParam("WithTotalCMC");
        int totcmc = AbilityUtils.calculateAmount(host, sa.getParam("WithTotalCMC"), sa);

        if (sa.hasParam("ChangeNum")) {
            if (sa.getParam("ChangeNum").equalsIgnoreCase("All")) {
                changeAll = true;
            }
            else if (sa.getParam("ChangeNum").equalsIgnoreCase("AllButOne")) {
                allButOne = true;
            }
            else {
                destZone1ChangeNum = AbilityUtils.calculateAmount(host, sa.getParam("ChangeNum"), sa);
            }
        }

        final TargetRestrictions tgt = sa.getTargetRestrictions();
        final List<Player> tgtPlayers = getDefinedPlayersOrTargeted(sa);

        CardZoneTable table = new CardZoneTable();
        GameEntityCounterTable counterTable = new GameEntityCounterTable();
        boolean combatChanged = false;
        CardCollectionView lastStateBattlefield = game.copyLastStateBattlefield();
        CardCollectionView lastStateGraveyard = game.copyLastStateGraveyard();

        for (final Player p : tgtPlayers) {
            if (tgt != null && !p.canBeTargetedBy(sa)) {
                continue;
            }
            final CardCollection top = new CardCollection();
            final CardCollection rest = new CardCollection();
            final PlayerZone sourceZone = p.getZone(srcZone);

            numToDig = Math.min(numToDig, sourceZone.size());
            for (int i = 0; i < numToDig; i++) {
                top.add(sourceZone.get(i));
            }

            if (!top.isEmpty()) {
                DelayedReveal delayedReveal = null;
                boolean hasRevealed = true;
                if (sa.hasParam("Reveal") && "True".equalsIgnoreCase(sa.getParam("Reveal"))) {
                        game.getAction().reveal(top, p, false);
                }
                else if (sa.hasParam("RevealOptional")) {
                    String question = TextUtil.concatWithSpace(Localizer.getInstance().getMessage("lblReveal") + ":", TextUtil.addSuffix(Lang.joinHomogenous(top),"?"));

                    hasRevealed = p.getController().confirmAction(sa, null, question);
                    if (hasRevealed) {
                        game.getAction().reveal(top, p);
                    }
                }
                else if (sa.hasParam("RevealValid")) {
                    final String revealValid = sa.getParam("RevealValid");
                    final CardCollection toReveal = CardLists.getValidCards(top, revealValid, cont, host, sa);
                    if (!toReveal.isEmpty()) {
                        game.getAction().reveal(toReveal, cont);
                        if (sa.hasParam("RememberRevealed")) {
                            host.addRemembered(toReveal);
                        }
                    }
                }
                else if (!sa.hasParam("NoLooking")) {
                    // show the user the revealed cards
                    delayedReveal = new DelayedReveal(top, srcZone, PlayerView.get(p), CardTranslation.getTranslatedName(host.getName()) + " - " + Localizer.getInstance().getMessage("lblLookingCardIn") + " ");

                    if (noMove) {
                        // Let the activating player see the cards even if they're not moved
                        game.getAction().revealTo(top, player);
                    }
                }

                if (sa.hasParam("RememberRevealed") && !sa.hasParam("RevealValid") && hasRevealed) {
                    host.addRemembered(top);
                }
                if (sa.hasParam("ImprintRevealed") && hasRevealed) {
                    host.addImprintedCards(top);
                }
                if (sa.hasParam("Choser")) {
                    final FCollectionView<Player> choosers = AbilityUtils.getDefinedPlayers(host, sa.getParam("Choser"), sa);
                    if (!choosers.isEmpty()) {
                        chooser = player.getController().chooseSingleEntityForEffect(choosers, null, sa, Localizer.getInstance().getMessage("lblChooser") + ":", false, p, null);
                    }
                    if (sa.hasParam("SetChosenPlayer")) {
                        host.setChosenPlayer(chooser);
                    }
                }
                if (!noMove) {
                    CardCollection movedCards;
                    rest.addAll(top);
                    CardCollection valid;
                    if (mitosis) {
                        valid = sharesNameWithCardOnBattlefield(game, top);
                    } else if (!changeValid.isEmpty()) {
                        if (changeValid.contains("ChosenType")) {
                            changeValid = changeValid.replace("ChosenType", host.getChosenType());
                        }
                        valid = CardLists.getValidCards(top, changeValid, cont, host, sa);
                        if (totalCMC) {
                            valid = CardLists.getValidCards(valid, "Card.cmcLE" + totcmc, cont, host, sa);
                        }
                    } else if (totalCMC) {
                        valid = CardLists.getValidCards(top, "Card.cmcLE" + totcmc, cont, host, sa);
                    } else {
                        // If all the cards are valid choices, no need for a separate reveal dialog to the chooser. pfps??
                        if (p == chooser && destZone1ChangeNum > 1) {
                            delayedReveal = null;
                        }
                        valid = top;
                    }

                    if (forceRevealToController) {
                        // Force revealing the card to the player activating the ability (e.g. Explorer's Scope)
                        game.getAction().revealTo(top, player);
                        delayedReveal = null; // top is already seen by the player, do not reveal twice
                    }

                    // Optional abilities that use a dialog box to prompt the user to skip the ability (e.g. Explorer's Scope, Quest for Ula's Temple)
                    if (optional && mayBeSkipped && !valid.isEmpty()) {
                        String prompt = !optionalAbilityPrompt.isEmpty() ? optionalAbilityPrompt : Localizer.getInstance().getMessage("lblWouldYouLikeProceedWithOptionalAbility") + " " + host + "?\n\n(" + sa.getDescription() + ")";
                        if (!p.getController().confirmAction(sa, null, TextUtil.fastReplace(prompt, "CARDNAME", CardTranslation.getTranslatedName(host.getName())))) {
                            return;
                        }
                    }

                    if (changeAll) {
                        movedCards = new CardCollection(valid);
                    } else if (sa.hasParam("RandomChange")) {
                        int numChanging = Math.min(destZone1ChangeNum, valid.size());
                        movedCards = CardLists.getRandomSubList(valid, numChanging);
                    } else if (totalCMC) {
                        movedCards = new CardCollection();
                        if (p == chooser) {
                            chooser.getController().tempShowCards(top);
                        }
                        if (valid.isEmpty()) {
                            chooser.getController().notifyOfValue(sa, null,
                                    Localizer.getInstance().getMessage("lblNoValidCards"));
                        }
                        boolean opt = false;
                        if (anyNumber) {
                            opt = true;
                        }
                        while (!valid.isEmpty()) {
                            Card chosen = chooser.getController().chooseSingleEntityForEffect(valid, delayedReveal, sa,
                                    Localizer.getInstance().getMessage("lblChooseOne"), opt, p, null);
                            if (chosen != null) {
                                movedCards.add(chosen);
                                valid.remove(chosen);
                                totcmc = totcmc - chosen.getCMC();
                                valid = CardLists.getValidCards(valid, "Card.cmcLE" + totcmc, cont, host, sa);
                            } else { //if they can and did choose nothing, we're done here
                                break;
                            }
                        }
                        chooser.getController().endTempShowCards();
                        if (!movedCards.isEmpty()) {
                            game.getAction().reveal(movedCards, chooser, true,
                                    Localizer.getInstance().getMessage("lblPlayerPickedChosen",
                                            chooser.getName(), ""));
                        }
                    } else if (sa.hasParam("ForEachColorPair")) {
                        movedCards = new CardCollection();
                        if (p == chooser) {
                            chooser.getController().tempShowCards(top);
                        }
                        for (final byte pair : MagicColor.COLORPAIR) {
                            Card chosen = chooser.getController().chooseSingleEntityForEffect(CardLists.filter(valid,
                                    CardPredicates.isExactlyColor(pair)), delayedReveal, sa,
                                    Localizer.getInstance().getMessage("lblChooseOne"), false, p, null);
                            if (chosen != null) {
                                movedCards.add(chosen);
                            }
                        }
                        chooser.getController().endTempShowCards();
                        if (!movedCards.isEmpty()) {
                            game.getAction().reveal(movedCards, chooser, true, Localizer.getInstance().getMessage("lblPlayerPickedChosen", chooser.getName(), ""));
                        }
                    } else if (allButOne) {
                        movedCards = new CardCollection(valid);
                        String prompt;
                        if (destZone2.equals(ZoneType.Library) && libraryPosition2 == 0) {
                            prompt = Localizer.getInstance().getMessage("lblChooseACardToLeaveTargetLibraryTop", p.getName());
                        } else {
                            prompt = Localizer.getInstance().getMessage("lblChooseACardLeaveTarget", p.getName(), destZone2.getTranslatedName());
                        }

                        Card chosen = chooser.getController().chooseSingleEntityForEffect(valid, delayedReveal, sa, prompt, false, p, null);
                        movedCards.remove(chosen);
                        if (sa.hasParam("RandomOrder")) {
                            CardLists.shuffle(movedCards);
                        }
                    } else {
                        String prompt;
                        
                        if (sa.hasParam("PrimaryPrompt")) {
                            prompt = sa.getParam("PrimaryPrompt");
                        } else {
                            prompt = Localizer.getInstance().getMessage("lblChooseCardsPutIntoZone", destZone1.getTranslatedName());
                            if (destZone1.equals(ZoneType.Library)) {
                                if (libraryPosition == -1) {
                                    prompt = Localizer.getInstance().getMessage("lblChooseCardPutOnTargetLibraryBottom", p.getName());
                                } else if (libraryPosition == 0) {
                                    prompt = Localizer.getInstance().getMessage("lblChooseCardPutOnTargetLibraryTop", p.getName());
                                }
                            }
                        }

                        movedCards = new CardCollection();
                        if (valid.isEmpty()) {
                            chooser.getController().notifyOfValue(sa, null, Localizer.getInstance().getMessage("lblNoValidCards"));
                        } else {
                            if (p == chooser) { // the digger can still see all the dug cards when choosing
                                chooser.getController().tempShowCards(top);
                            }
                            List<Card> chosen = new ArrayList<>();

                            int max = anyNumber ? valid.size() : Math.min(valid.size(), destZone1ChangeNum);
                            int min = (anyNumber || optional) ? 0 : max;
                            if (max > 0) { // if max is 0 don't make a choice
                                chosen = chooser.getController().chooseEntitiesForEffect(valid, min, max, delayedReveal, sa, prompt, p, null);
                            }

                            chooser.getController().endTempShowCards();
                            movedCards.addAll(chosen);
                        }

                        if (!changeValid.isEmpty() && !sa.hasParam("ExileFaceDown") && !sa.hasParam("NoReveal")) {
                            game.getAction().reveal(movedCards, chooser, true, Localizer.getInstance().getMessage("lblPlayerPickedCardFrom", chooser.getName()));
                        }
                    }
                    if (sa.hasParam("ForgetOtherRemembered")) {
                        host.clearRemembered();
                    }
                    Collections.reverse(movedCards);

                    if (destZone1.equals(ZoneType.Battlefield)) {
                        movedCards = (CardCollection) GameActionUtil.orderCardsByTheirOwners(game, movedCards, destZone1, sa);
                    }

                    Card effectHost = sa.getOriginalHost();
                    if (effectHost == null) {
                        effectHost = sa.getHostCard();
                    }
                    for (Card c : movedCards) {
                        final ZoneType origin = c.getZone().getZoneType();
                        final PlayerZone zone = c.getOwner().getZone(destZone1);

                        if (zone.is(ZoneType.Library) || zone.is(ZoneType.PlanarDeck) || zone.is(ZoneType.SchemeDeck)) {
                            if (libraryPosition == -1 || libraryPosition > zone.size()) {
                                libraryPosition = zone.size();
                            }
                            c = game.getAction().moveTo(zone, c, libraryPosition, sa);
                        } else {
                            Map<AbilityKey, Object> moveParams = AbilityKey.newMap();
                            moveParams.put(AbilityKey.LastStateBattlefield, lastStateBattlefield);
                            moveParams.put(AbilityKey.LastStateGraveyard, lastStateGraveyard);
                            c = game.getAction().moveTo(zone, c, sa, moveParams);
                            if (destZone1.equals(ZoneType.Battlefield)) {
                                if (sa.hasParam("Tapped")) {
                                    c.setTapped(true);
                                }
                                if (addToCombat(c, c.getController(), sa, "Attacking", "Blocking")) {
                                    combatChanged = true;
                                }
                            } else if (destZone1.equals(ZoneType.Exile)) {
                                if (sa.hasParam("ExileWithCounter")) {
                                    c.addCounter(CounterType.getType(sa.getParam("ExileWithCounter")), 1, player, counterTable);
                                }
                                c.setExiledWith(effectHost);
                                c.setExiledBy(effectHost.getController());
                            }
                        }
                        if (!origin.equals(c.getZone().getZoneType())) {
                            table.put(origin, c.getZone().getZoneType(), c);
                        }

                        if (sa.hasParam("ExileFaceDown")) {
                            c.turnFaceDown(true);
                        }
                        if (sa.hasParam("WithMayLook")) {
                            c.addMayLookFaceDownExile(c.getOwner());
                        }
                        if (sa.hasParam("Imprint")) {
                            host.addImprintedCard(c);
                        }
                        if (sa.hasParam("ForgetOtherRemembered")) {
                            host.clearRemembered();
                        }
                        if (remZone1) {
                            host.addRemembered(c);
                        }
                        rest.remove(c);
                    }

                    // now, move the rest to destZone2
                    if (destZone2 == ZoneType.Library || destZone2 == ZoneType.PlanarDeck || destZone2 == ZoneType.SchemeDeck
                            || destZone2 == ZoneType.Graveyard) {
                        CardCollection afterOrder = rest;
                        if (sa.hasParam("RestRandomOrder")) {
                            CardLists.shuffle(afterOrder);
                        } else if (!skipReorder && rest.size() > 1) {
                            if (destZone2 == ZoneType.Graveyard) {
                                afterOrder = (CardCollection) GameActionUtil.orderCardsByTheirOwners(game, rest, destZone2, sa);
                            } else {
                                afterOrder = (CardCollection) chooser.getController().orderMoveToZoneList(rest, destZone2, sa);
                            }
                        }
                        if (libraryPosition2 != -1) {
                            // Closest to top
                            Collections.reverse(afterOrder);
                        }
                        for (final Card c : afterOrder) {
                            final ZoneType origin = c.getZone().getZoneType();
                            Card m;
                            if (destZone2 == ZoneType.Library) {
                                m = game.getAction().moveToLibrary(c, libraryPosition2, sa);
                            } else {
                                m = game.getAction().moveToVariantDeck(c, destZone2, libraryPosition2, sa);
                            }
                            if (m != null && !origin.equals(m.getZone().getZoneType())) {
                                table.put(origin, m.getZone().getZoneType(), m);
                            }
                            if (remZone2) {
                                host.addRemembered(m);
                            }
                        }
                    } else {
                        // just move them randomly
                        for (int i = 0; i < rest.size(); i++) {
                            Card c = rest.get(i);
                            final ZoneType origin = c.getZone().getZoneType();
                            final PlayerZone toZone = c.getOwner().getZone(destZone2);
                            c = game.getAction().moveTo(toZone, c, sa);
                            if (!origin.equals(c.getZone().getZoneType())) {
                                table.put(origin, c.getZone().getZoneType(), c);
                            }
                            if (destZone2 == ZoneType.Exile) {
                                if (sa.hasParam("ExileWithCounter")) {
                                    c.addCounter(CounterType.getType(sa.getParam("ExileWithCounter")), 1, player, counterTable);
                                }
                                c.setExiledWith(effectHost);
                                c.setExiledBy(effectHost.getController());
                                if (remZone2) {
                                    host.addRemembered(c);
                                }
                            }
                        }
                    }
                }
            }
        }
        if (combatChanged) {
            game.updateCombatForView();
            game.fireEvent(new GameEventCombatChanged());
        }
        //table trigger there
        table.triggerChangesZoneAll(game, sa);
        counterTable.replaceCounterEffect(game, sa, true);
    }

    // TODO This should be somewhere else, maybe like CardUtil or something like that
    // returns a List<Card> that is a subset of list with cards that share a name
    // with a permanent on the battlefield
    private static CardCollection sharesNameWithCardOnBattlefield(final Game game, final List<Card> list) {
        final CardCollection toReturn = new CardCollection();
        final CardCollectionView play = game.getCardsIn(ZoneType.Battlefield);
        for (final Card c : list) {
            for (final Card p : play) {
                if (p.sharesNameWith(c) && !toReturn.contains(c)) {
                    toReturn.add(c);
                }
            }
        }
        return toReturn;
    }
}

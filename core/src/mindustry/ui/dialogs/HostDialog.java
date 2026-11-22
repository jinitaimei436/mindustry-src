package mindustry.ui.dialogs;

import arc.*;
import arc.scene.ui.*;
import arc.util.*;
import mindustry.game.EventType.*;
import mindustry.game.coop.*;
import mindustry.gen.*;
import mindustry.net.*;
import mindustry.ui.*;

import java.util.*;

import static mindustry.Vars.*;

public class HostDialog extends BaseDialog{
    float w = 300;

    public HostDialog(){
        super("@hostserver");

        addCloseButton();

        cont.table(t -> {
            t.add("@name").padRight(10);
            t.field(Core.settings.getString("name"), text -> {
                player.name(text);
                Core.settings.put("name", text);
                ui.listfrag.rebuild();
            }).grow().pad(8).get().setMaxLength(40);

            ImageButton button = t.button(Tex.whiteui, Styles.squarei, 40, () -> {
                new PaletteDialog().show(color -> {
                    player.color.set(color);
                    Core.settings.put("color-0", color.rgba());
                });
            }).size(54f).get();
            button.update(() -> button.getStyle().imageUpColor = player.color());
        }).width(w).height(70f).pad(4).colspan(3);

        if(steam){
            cont.row();

            cont.add().width(65f);

            cont.check("@steam.friendsonly", !Core.settings.getBool("steampublichost2"), val -> Core.settings.put("steampublichost2", !val)).colspan(2).left()
                .with(c -> ui.addDescTooltip(c, "@steam.friendsonly.tooltip")).padBottom(15f).row();
        }

        cont.row();

        TextField[] portField = {null};

        cont.table(t -> {
            t.add("@server.port").padRight(10);
            portField[0] = t.field(String.valueOf(Core.settings.getInt("port", port)), text -> Core.settings.put("port", Strings.parseInt(text, 6567)))
            .pad(8).grow().maxTextLength(5).valid(text -> {
                int port = Strings.parseInt(text);
                return port >= 1 && port <= 65535;
            }).get();
        }).width(w).height(70f).pad(4).colspan(3);

        cont.row();

        cont.table(p -> {
            p.left().defaults().left().pad(3f);
            var perms = MultiSectorCoordinator.get().permissions;
            p.add("[accent]战役联机房客权限 (V2)").row();
            p.check("允许房客切换已有区块", perms.allowGuestSectorSwitch, val -> {
                perms.allowGuestSectorSwitch = val;
                MultiSectorCoordinator.get().broadcastPermissions();
            }).row();
            p.check("允许房客发射核心到新区块", perms.allowGuestCoreLaunch, val -> {
                perms.allowGuestCoreLaunch = val;
                MultiSectorCoordinator.get().broadcastPermissions();
            }).row();
            p.check("允许房客研究共享科技树", perms.allowGuestResearch, val -> {
                perms.allowGuestResearch = val;
                MultiSectorCoordinator.get().broadcastPermissions();
            }).row();
            p.check("允许房客放弃区块", perms.allowGuestAbandon, val -> {
                perms.allowGuestAbandon = val;
                MultiSectorCoordinator.get().broadcastPermissions();
            }).row();
        }).width(w + 60f).pad(6f).colspan(3);

        cont.row();

        cont.add().width(65f);

        cont.button("@host", () -> {
            if(Core.settings.getString("name").trim().isEmpty()){
                ui.showInfo("@noname");
                return;
            }

            runHost();
        }).width(w).height(70f).disabled(b -> !portField[0].isValid());

        if(!steam){
            cont.button("?", () -> ui.showInfo("@host.info")).size(65f, 70f).padLeft(6f);
        }else{
            cont.add().size(65f, 70f).padLeft(6f);
        }

        shown(() -> {
            if(!steam){
                Core.app.post(() -> Core.settings.getBoolOnce("hostinfo", () -> ui.showInfo("@host.info")));
            }
        });
    }

    public void runHost(){
        ui.loadfrag.show("@hosting");
        Time.runTask(5f, () -> {
            try{
                if(steam){
                    SteamAdmin.fetch();
                }

                net.host(Core.settings.getInt("port", port));
                player.admin = true;
                Events.fire(new HostEvent());
                MultiSectorCoordinator.get().onHostStarted();
            }catch(Exception e){
                ui.showException(e.getMessage() != null && e.getMessage().toLowerCase(Locale.ROOT).contains("address already in use") ? "@server.error.addressinuse" : "@server.error", e);
            }
            ui.loadfrag.hide();
            hide();
        });
    }
}

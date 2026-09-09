-- VLAN 0 est réservé et ne peut pas être assigné à un réseau 802.1Q.
-- Ne jamais corriger silencieusement une donnée existante : l'opérateur doit
-- choisir le VID de remplacement avant que la migration puisse continuer.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM vlans WHERE vid = 0) THEN
        RAISE EXCEPTION 'VLAN 0 exists and must be reassigned before upgrading Subnetory'
            USING HINT = 'Update or delete every VLAN with vid=0, then restart the migration.';
    END IF;
END $$;

ALTER TABLE vlans DROP CONSTRAINT IF EXISTS vlans_vid_check;
ALTER TABLE vlans
    ADD CONSTRAINT chk_vlans_vid_assignable CHECK (vid BETWEEN 1 AND 4094);

# Shared Control Impostor

Plugin Minecraft mini-game dimana semua pemain bermain **bergantian** mengontrol satu karakter di survival mode, dengan satu atau lebih **impostor** yang berusaha menggagalkan objektif.

---

## Requirement

- **Minecraft**: 1.21.11
- **Server**: Paper (API-version 1.21.11)
- **Java**: 21+

---

## Cara Install

1. Build plugin dengan Maven:
   ```bash
   mvn clean package
   ```
2. Copy file `.jar` dari folder `target/` ke folder `plugins/` server.
3. Restart server.

---

## Alur Permainan

1. **Registrasi pemain** dengan `/regis [player]` atau `/regisall`
2. **Konfigurasi** settings melalui item **Settings** (Test Block) di inventory — hanya untuk OP
3. **Mulai game** dengan `/start`
4. Pemain bermain **bergantian** di survival world
5. Saat giliran habis, karakter berpindah ke pemain berikutnya beserta seluruh **state** (inventory, health, posisi, dll.)
6. Investigator harus menyelesaikan **objektif**, impostor harus mencegahnya
7. Jika diperlukan, panggil **meeting** dengan `/meeting` untuk voting eliminasi
8. Game berakhir jika semua objektif selesai (Investigator menang) atau impostor mendominasi (Impostor menang)

---

## Commands

| Command | Deskripsi | Permission |
|---------|-----------|------------|
| `/regis [player]` | Daftarkan pemain ke game | OP |
| `/regisall` | Daftarkan semua pemain online | OP |
| `/unregis [player]` | Hapus pemain dari daftar | OP |
| `/start` | Mulai game | OP |
| `/endgame` | Akhiri game dan kembali ke lobby | OP |
| `/meeting` | Panggil meeting (voting) | Semua pemain |
| `/skip` | Lewati giliran aktifmu | Pemain aktif |
| `/listplayer` | Lihat daftar pemain terdaftar | Semua pemain |
| `/commandinfo` | Lihat info command dan settings | OP |

---

## Settings (Menu OP)

Klik kanan item **Settings** untuk membuka menu konfigurasi.

| Slot | Item | Fungsi |
|------|------|--------|
| 0 | Totem | Toggle **One Life** mode (mati = impostor menang) |
| 1 | Grass/Dirt Block | Toggle **Buat world baru** / **Lanjutkan world sebelumnya** |
| 2 | Clock | Konfigurasi **Timer** (swap, voting, cooldown meeting) |
| 3 | Target | Konfigurasi **Objective** (mode, type, template, custom) |
| 4 | Name Tag | Konfigurasi **jumlah impostor** |

---

## Mode Objektif

### One Objective
Semua investigator berbagi 1 objektif yang sama.

### Own Objective
Setiap investigator mendapat objektif masing-masing. Jika seorang investigator dieliminasi, objektifnya menjadi objektif bersama yang bisa diselesaikan siapa saja.

---

## Tipe Objektif

| Tipe | Deskripsi |
|------|-----------|
| **Random** | Objektif dipilih secara acak dari pool template |
| **Template** | Pilih sendiri objektif dari daftar yang tersedia |
| **Custom** | Buat objektif custom dengan 4 input: Nama, Aksi (Mining/Pickup/Kill), Target ID, dan Jumlah |

### Contoh Custom Objective
- Mining 32 Diamond Ore → Aksi: `Mining Block`, Target: `diamond_ore`, Jumlah: `32`
- Dapat 10 Arrow → Aksi: `Dapatkan Item`, Target: `arrow`, Jumlah: `10`
- Bunuh 3 Creeper → Aksi: `Bunuh Mob`, Target: `creeper`, Jumlah: `3`

> **Catatan**: Target ID menggunakan nama material/entity Minecraft (lowercase). Contoh: `diamond_ore`, `warden`, `iron_sword`.

---

## Objektif Template (One Objective)

| ID | Deskripsi |
|----|-----------|
| one_nether | Masuk ke dimensi nether |
| one_piglin_pearl | Dapatkan ender pearl dari piglin |
| one_ghast_tear | Dapatkan 1 Ghast Tear |
| one_ancient_debris | Dapatkan 1 Ancient Debris |
| one_wither_skull | Dapatkan 1 Wither Skeleton Skull |
| one_breeze | Bunuh 5 Breeze |
| one_elder_guardian | Bunuh Elder Guardian |
| one_warden_death | Bunuh diri dengan Warden |
| one_ender_dragon | Kalahkan Ender Dragon |
| one_elytra | Dapatkan Elytra |

## Objektif Template (Own Objective)

| ID | Deskripsi |
|----|-----------|
| own_trade_villager | Trade dengan Villager |
| own_enchant | Gunakan Enchanting Table |
| own_tame_cat | Tame Cat |
| own_axolotl_bucket | Dapatkan Bucket of Axolotl |
| own_hit_golem | Pukul Iron Golem |
| own_ride_horse | Tunggangi Horse sejauh 100 block |
| own_pufferfish | Makan Pufferfish |
| own_bedrock_height | Capai ketinggian bedrock |
| own_mlg_water | MLG Water Bucket dari ketinggian 20 block |
| own_gapple | Makan 1 Golden Apple |
| own_chicken_jockey | Bunuh Chicken Jockey |
| own_music_disc | Dapatkan Music Disc dari Creeper |
| own_mine_stone | Mining 64 stone |
| own_eat_cake | Makan Cake |
| own_5_wool | Kumpulkan 5 warna Wool berbeda |
| own_sprint_500 | Berlari sejauh 500 block |
| own_iron_armor | Pakai full set Iron Armor |
| own_honey_bottle | Dapatkan honey bottle |
| own_5_biomes | Pergi ke 5 biome berbeda |
| own_ignite_tnt | Nyalakan 5 TNT |
| own_nether | Masuk ke Nether |

---

## Sistem World

- Saat `/start` dengan **Buat world baru** dipilih: survival world baru dibuat dan world lama dihapus
- Saat `/start` dengan **Lanjutkan world sebelumnya**: world survival terakhir dimuat kembali beserta posisi dan inventory pemain aktif terakhir
- `/endgame` **tidak** menghapus world, hanya mengembalikan semua pemain ke lobby
- `keepInventory` diset **true** secara default di semua survival world

---

## Sistem Meeting

- Gunakan `/meeting` untuk memanggil voting
- Ada **cooldown** yang bisa dikonfigurasi via Settings
- Cooldown mulai berjalan setelah pemain aktif **bergerak** pertama kali setelah meeting selesai
- Setiap pemain hanya bisa memanggil **1 meeting** per sesi game
- Jika voting seri (suara sama banyak), tidak ada yang dieliminasi

---

## Developer

- **Author**: czipo
- **API**: Paper 1.21.11

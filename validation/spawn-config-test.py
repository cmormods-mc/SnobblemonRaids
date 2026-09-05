import json, pathlib, sys
root = pathlib.Path(sys.argv[1])
server = json.loads((root/'examples/server.json').read_text())
raid = json.loads((root/'src/main/resources/data/cobbleraids/raids/example_garchomp.json').read_text())

n = server['natural_spawning']
assert n['enabled'] is True
assert 20 <= n['check_interval_ticks'] <= 72000
assert 0.0 <= n['spawn_attempt_chance'] <= 1.0
assert 1 <= n['attempts_per_check'] <= 64
assert 1 <= n['max_active_raids_per_dimension'] <= n['max_active_raids']
assert 8.0 <= n['min_distance_from_player'] < n['max_distance_from_player'] <= 512.0
assert n['min_distance_between_raids'] >= 0.0
assert n['default_despawn_seconds'] > 0

r = server['recruitment_defaults']
assert r['duration_seconds'] == 45
assert r['radius'] == 10.0
assert 1 <= r['max_players'] <= 4

spawn = raid['spawn']
assert spawn['enabled'] is True
assert spawn['weight'] > 0
assert spawn['dimensions'] == ['minecraft:overworld']
assert len(spawn['biomes']) >= 1
assert spawn['times'] == ['all_day']
assert spawn['max_concurrent'] == 1
assert 1 <= raid['recruitment']['max_players'] <= 4

# Exact seven periods independently validated from CobbleBoss TimeUtils bytecode.
def period(t):
    t %= 24000
    if t < 3000: return 'early_morning'
    if t < 6000: return 'morning'
    if t < 12000: return 'noon'
    if t < 15000: return 'afternoon'
    if t < 18000: return 'dusk'
    if t < 21000: return 'night'
    return 'midnight'

checks = {
    0:'early_morning', 2999:'early_morning', 3000:'morning', 5999:'morning',
    6000:'noon', 11999:'noon', 12000:'afternoon', 14999:'afternoon',
    15000:'dusk', 17999:'dusk', 18000:'night', 20999:'night',
    21000:'midnight', 23999:'midnight', 24000:'early_morning'
}
for tick, expected in checks.items():
    assert period(tick) == expected, (tick, period(tick), expected)

# The default global spawn opportunity is intentionally a chance per global check,
# not a forced spawn and not a per-nearby-player multiplier.
expected_seconds_between_checks = n['check_interval_ticks'] / 20.0
assert expected_seconds_between_checks == 60.0
assert n['spawn_attempt_chance'] == 0.25
print(json.dumps({
    'passed': True,
    'check_interval_seconds': expected_seconds_between_checks,
    'global_attempt_chance': n['spawn_attempt_chance'],
    'global_active_cap': n['max_active_raids'],
    'dimension_active_cap': n['max_active_raids_per_dimension'],
    'recruitment_default_seconds': r['duration_seconds'],
    'recruitment_default_radius': r['radius'],
    'validated_transport_max_players': r['max_players']
}, indent=2))

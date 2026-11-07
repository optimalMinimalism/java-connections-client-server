"""Verifiche riproducibili su JAR, configurazioni e dati temporanei."""
import base64
import concurrent.futures
import json
import pathlib
import shutil
import socket
import subprocess
import tempfile
import time

ROOT = pathlib.Path(__file__).resolve().parents[1]

def port(socket_type=socket.SOCK_STREAM):
    with socket.socket(socket.AF_INET, socket_type) as s:
        s.bind(('127.0.0.1', 0))
        return s.getsockname()[1]

class Client:
    def __init__(self, tcp_port):
        self.socket = socket.create_connection(('127.0.0.1', tcp_port), timeout=6)
        self.file = self.socket.makefile('rw')
        self.udp = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.udp.bind(('127.0.0.1', 0))
        self.udp.settimeout(6)
    def ask(self, operation, **fields):
        self.file.write(json.dumps(dict(operation=operation, **fields)) + '\n')
        self.file.flush()
        return json.loads(self.file.readline())
    def login(self, name):
        return self.ask('login', username=name, psw='pw', udpPort=self.udp.getsockname()[1])
    def info(self, game_id=-1):
        return self.ask('requestGameInfo', gameId=game_id)['payload']['gameInfo']
    def event(self, kind):
        parts = {}
        until = time.monotonic() + 8
        while time.monotonic() < until:
            fragment = json.loads(self.udp.recv(4096))
            key = fragment['eventId']
            group = parts.setdefault(key, {})
            group[fragment['index']] = base64.b64decode(fragment['data'])
            if len(group) == fragment['count']:
                event = json.loads(b''.join(group[i] for i in range(fragment['count'])))
                if event['type'] == kind:
                    return json.loads(event['message'])
        raise AssertionError('Evento non ricevuto: ' + kind)
    def close(self):
        self.file.close()
        self.socket.close()
        self.udp.close()

with tempfile.TemporaryDirectory(prefix='connections-test-') as directory:
    work = pathlib.Path(directory)
    for name in ('config', 'data', 'lib', 'dist'):
        (work / name).mkdir()
    shutil.copy(ROOT / 'lib/gson.jar', work / 'lib/gson.jar')
    for name in ('server.jar', 'client.jar'):
        shutil.copy(ROOT / 'dist' / name, work / 'dist' / name)
    puzzles = []
    for number in range(2):
        puzzles.append(dict(gameId=99, groups=[dict(theme=str(g), words=[f'p{number}g{g}w{i}' for i in range(4)]) for g in range(4)]))
    (work / 'data/puzzles.json').write_text(json.dumps(puzzles))
    tcp_port = port()
    (work / 'config/server-config.properties').write_text(f'''server.host=127.0.0.1
server.tcp.read.timeout.seconds=30
server.tcp.port={tcp_port}
server.threadpool.size=16
server.game.duration.seconds=4
server.persistence.period.seconds=1
server.data.puzzles.path=data/puzzles.json
server.data.games.path=data/games.json
''')
    (work / 'config/client-config.properties').write_text(f'server.host=127.0.0.1\nserver.tcp.port={tcp_port}\nclient.udp.port={port()}\nclient.udp.fragment.timeout.seconds=10\nclient.udp.receive.timeout.millis=500\n')
    def start():
        process = subprocess.Popen(['java', '-jar', 'dist/server.jar'], cwd=work, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        for _ in range(100):
            try:
                c = Client(tcp_port)
                c.close()
                return process
            except OSError:
                if process.poll() is not None:
                    raise AssertionError(process.communicate())
                time.sleep(.03)
        raise AssertionError('Avvio server fallito')
    def stop(process):
        process.terminate()
        out, err = process.communicate(timeout=8)
        assert not err, err.decode()
        snapshot = json.loads((work / 'data/games.json.snapshot.json').read_text())
        games = {game['gameId']: game for game in snapshot['historicalGames']}
        if snapshot.get('currentGame'):
            games[snapshot['currentGame']['gameId']] = snapshot['currentGame']
        for account in snapshot['users'].values():
            completed = []
            for game in games.values():
                state = game['playerStates'].get(account['accountId'])
                if state:
                    assert state['accountId'] == account['accountId'] and 'username' not in state
                if state and state['outcome'] != 'PLAYING':
                    completed.append(state)
            assert account['totalScore'] == sum(state['currentScore'] for state in completed)
            assert account['stats']['gamesPlayed'] == len(completed)
    server = start()
    clients = []
    try:
        a, b, other = [Client(tcp_port) for _ in range(3)]
        clients += [a, b, other]
        for name in ('alice', 'bob'):
            assert a.ask('register', username=name, psw='pw')['status'] == 'ok'
        assert a.ask('register', username='alice', psw='pw')['status'] == 'error'
        assert a.ask('login', username='alice', psw='wrong')['status'] == 'error'
        assert a.login('alice')['status'] == 'ok'
        assert b.login('alice')['status'] == 'ok'
        assert other.login('bob')['status'] == 'ok'
        # Il protocollo deve rifiutare input malformati senza perdere la connessione.
        bad = Client(tcp_port)
        try:
            for line in ('null', '[]', '{"operation":true}', '{bad-json'):
                bad.file.write(line + '\n'); bad.file.flush()
                assert json.loads(bad.file.readline())['code'] == 'INVALID_REQUEST'
            assert bad.ask('requestGameInfo', gameId=-1)['code'] == 'USER_NOT_LOGGED_IN'
            bad.file.write('{"operation":"register","username":"' + 'x' * 17000 + '","psw":"pw"}\n')
            bad.file.flush()
            assert json.loads(bad.file.readline())['code'] == 'INVALID_REQUEST'
            assert bad.file.readline() == ''
        finally:
            bad.close()
        game_id = a.info()['gameId']
        # Un client fermo a metà richiesta non deve bloccare lo stato del gioco.
        with socket.create_connection(('127.0.0.1', tcp_port), timeout=6) as slow:
            slow.sendall(b'{"operation":')
            assert a.info()['gameId'] == game_id
        assert a.ask('submitProposal', words=['bad']*4)['payload']['result']['type'] == 'malformed'
        assert a.info()['wrongProposals'] == 0
        for group in puzzles[0]['groups'][:3]:
            proposal = a.ask('submitProposal', words=group['words'])['payload']
            assert proposal['result']['type'] == 'correct'
        assert len(proposal['gameInfo']['solutionGroups']) == 4
        assert a.info()['currentScore'] == 18
        assert len(a.info()['solutionGroups']) == 4
        event = a.event('PLAYER_FINISHED')
        assert len(event['gameInfo']['solutionGroups']) == 4
        bob_event = other.event('PLAYER_FINISHED')
        assert 'solutionGroups' not in bob_event['gameInfo']
        assert len(bob_event['gameLeaderboard']) == 2
        assert a.ask('updateCredentials', oldUsername='alice', oldPsw='pw', newUsername='renamed')['status'] == 'ok'
        assert b.info()['outcome'] == 'WON'
        logout = a.ask('logout')
        assert logout['status'] == 'ok' and logout['payload']['username'] == 'renamed'
        assert a.login('renamed')['payload']['currentGame']['outcome'] == 'WON'
        assert a.ask('submitProposal', words=puzzles[0]['groups'][3]['words'])['code'] == 'PLAYER_ALREADY_FINISHED'
        wrong = [puzzles[0]['groups'][i]['words'][0] for i in range(4)]
        for _ in range(4):
            proposal = other.ask('submitProposal', words=wrong)['payload']
            assert proposal['result']['type'] == 'wrong'
        assert len(proposal['gameInfo']['solutionGroups']) == 4
        # Senza ricevere UDP si recuperano esito e classifica con le operazioni TCP previste.
        manual = Client(tcp_port)
        try:
            assert manual.ask('login', username='renamed', psw='pw', udpPort=0)['status'] == 'ok'
            assert len(manual.info(game_id)['solutionGroups']) == 4
            assert len(manual.ask('requestGameStats', gameId=game_id)['payload']['gameLeaderboard']) == 2
        finally:
            manual.close()
        assert other.info()['currentScore'] == -16
        assert len(other.info()['solutionGroups']) == 4
        stats = a.ask('requestPlayerStats')['payload']['playerStats']
        assert stats['puzzlesCompleted'] == 1 and stats['perfectPuzzles'] == 1
        assert other.ask('logout')['status'] == 'ok'
        b.close(); clients.remove(b)
        # Riavvio mentre la partita è ancora valida: stessa scadenza persistita.
        stop(server)
        snapshot_path = work / 'data/games.json.snapshot.json'
        snapshot = json.loads(snapshot_path.read_text())
        deadline = snapshot['currentGame']['endTimeEpochMillis']
        for c in clients: c.close()
        clients.clear()
        server = start()
        a = Client(tcp_port); clients.append(a)
        assert a.login('renamed')['payload']['currentGame']['gameId'] == game_id
        time.sleep(.2)
        assert a.info()['outcome'] == 'WON'
        ended = a.event('GAME_ENDED')
        assert ended['gameStats']['participants'] == 2
        started = a.event('NEW_GAME_STARTED')
        assert started['gameInfo']['gameId'] > game_id
        assert len(started['gameInfo']['visibleWords']) == 16
        assert started['gameStats']['playersStillPlaying'] == 1
        # Richieste indipendenti e concorrenti sul medesimo account.
        def query(_):
            c = Client(tcp_port)
            try:
                assert c.login('renamed')['status'] == 'ok'
                assert c.ask('requestLeaderboard')['status'] == 'ok'
                return c.ask('submitProposal', words=puzzles[1]['groups'][0]['words'])['payload']['result']['type']
            finally: c.close()
        with concurrent.futures.ThreadPoolExecutor(max_workers=6) as pool:
            results = list(pool.map(query, range(12)))
            assert results.count('correct') == 1 and results.count('malformed') == 11
        assert a.info()['correctGroupsFound'] == 1
        assert a.ask('requestGameStats', gameId=-1)['payload']['gameLeaderboard'][0]['score'] == 6
        second = a.info()['gameId']
        a.event('GAME_ENDED'); third = a.event('NEW_GAME_STARTED')['gameInfo']['gameId']
        assert third > second # Fine dataset e riutilizzo senza sovrascrivere lo storico.
        assert a.info(game_id)['outcome'] == 'WON'
        client_run = subprocess.run(['java', '-jar', 'dist/client.jar'], cwd=work, input='0\n', text=True, capture_output=True, timeout=5)
        assert client_run.returncode == 0, client_run.stderr
        client_eof = subprocess.run(['java', '-jar', 'dist/client.jar'], cwd=work,
                                    input='', text=True, capture_output=True, timeout=5)
        assert client_eof.returncode == 0, client_eof.stderr
        # Prova il ricevitore reale: pulizia nel silenzio UDP e sopravvivenza a JSON malformato.
        udp_port = port(socket.SOCK_DGRAM)
        (work / 'config/client-config.properties').write_text(
            f'server.host=127.0.0.1\nserver.tcp.port={tcp_port}\nclient.udp.port={udp_port}\n'
            'client.udp.fragment.timeout.seconds=1\nclient.udp.receive.timeout.millis=50\n')
        output_path = work / 'client-output.txt'
        with output_path.open('w') as output:
            cli = subprocess.Popen(['java', '-jar', 'dist/client.jar'], cwd=work,
                                   stdin=subprocess.PIPE, stdout=output, stderr=subprocess.PIPE, text=True)
            def wait_for(text):
                deadline = time.monotonic() + 4
                while time.monotonic() < deadline:
                    if text in output_path.read_text(): return
                    if cli.poll() is not None: raise AssertionError(cli.stderr.read())
                    time.sleep(.03)
                raise AssertionError('Output client mancante: ' + text + '\n' + output_path.read_text())
            try:
                cli.stdin.write('2\nrenamed\npw\n'); cli.stdin.flush()
                wait_for('login successful')
                with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sender:
                    sender.sendto(b'{invalid', ('127.0.0.1', udp_port))
                    payload = dict(gameInfo=dict(outcome='WON', currentScore=18),
                                   gameLeaderboard=[dict(rank=1, username='udp_receiver_test', score=18)])
                    message = json.dumps(dict(type='GAME_ENDED', gameId=1, message=json.dumps(payload))).encode()
                    envelope = dict(eventId='test-full', index=0, count=1, data=base64.b64encode(message).decode())
                    datagram = json.dumps(envelope).encode()
                    # Attende una conferma: l'avvio del thread non garantisce che la porta sia già aperta.
                    deadline = time.monotonic() + 4
                    while 'udp_receiver_test' not in output_path.read_text() and time.monotonic() < deadline:
                        sender.sendto(datagram, ('127.0.0.1', udp_port))
                        time.sleep(.05)
                    wait_for('udp_receiver_test')
                    for _ in range(3):
                        sender.sendto(datagram, ('127.0.0.1', udp_port))
                    sender.sendto(json.dumps(dict(eventId='test-missing', index=0, count=2,
                                  data=base64.b64encode(b'{').decode())).encode(), ('127.0.0.1', udp_port))
                wait_for('Notifica UDP incompleta')
                assert output_path.read_text().count('udp_receiver_test') == 1
                cli.stdin.write('0\n'); cli.stdin.flush()
                _, errors = cli.communicate(timeout=5)
                assert cli.returncode == 0 and not errors, errors
            finally:
                if cli.poll() is None:
                    cli.kill(); cli.communicate(timeout=5)
        stop(server)
        for c in clients: c.close()
        clients.clear()
        # Arresto forzato: nessun salvataggio dello shutdown hook.
        server = start()
        crash_client = Client(tcp_port)
        try:
            assert crash_client.login('renamed')['status'] == 'ok'
            time.sleep(1.2) # Il salvataggio periodico è configurato ogni secondo.
            server.kill()
            server.communicate(timeout=5)
        finally:
            crash_client.close()
        crash_snapshot = json.loads(snapshot_path.read_text())
        expected_score = crash_snapshot['users']['renamed']['totalScore']
        server = start()
        restarted = Client(tcp_port)
        try:
            assert restarted.login('renamed')['status'] == 'ok'
            assert restarted.ask('requestPlayerStats')['payload']['playerStats']['totalScore'] == expected_score
        finally:
            restarted.close()
        stop(server)
        snapshot = json.loads(snapshot_path.read_text())
        snapshot['currentGame']['endTimeEpochMillis'] = int(time.time()*1000)-1
        expired = snapshot['currentGame']['gameId']
        snapshot_path.write_text(json.dumps(snapshot))
        server = start()
        a = Client(tcp_port); clients.append(a)
        assert a.login('renamed')['payload']['currentGame']['gameId'] > expired
        assert a.info(expired)['status'] == 'ENDED'
        stop(server)
        # Capacità del pool e scadenza delle connessioni inattive.
        config_path = work / 'config/server-config.properties'
        normal_config = config_path.read_text()
        config_path.write_text(normal_config.replace('server.threadpool.size=16', 'server.threadpool.size=2')
                              .replace('server.tcp.read.timeout.seconds=30', 'server.tcp.read.timeout.seconds=1'))
        server = start()
        held = []
        try:
            for _ in range(2):
                client = Client(tcp_port)
                held.append(client)
                assert client.ask('requestPlayerStats')['code'] == 'USER_NOT_LOGGED_IN'
            with socket.create_connection(('127.0.0.1', tcp_port), timeout=2) as excess:
                excess.settimeout(2)
                assert excess.recv(1) == b''
            for client in held:
                assert client.file.readline() == ''
            # Gli slot tornano disponibili dopo il timeout.
            probe = Client(tcp_port)
            try:
                assert probe.ask('requestPlayerStats')['code'] == 'USER_NOT_LOGGED_IN'
            finally:
                probe.close()
        finally:
            for client in held: client.close()
        stop(server)
        config_path.write_text(normal_config)
        # Un avvio fallito non deve riscrivere lo snapshot danneggiato.
        valid_snapshot = snapshot_path.read_text()
        try:
            snapshot_path.write_text('{}')
            failed = subprocess.run(['java', '-jar', 'dist/server.jar'], cwd=work,
                                    capture_output=True, text=True, timeout=5)
            assert failed.returncode != 0 and 'Snapshot incompleto' in failed.stderr
            assert snapshot_path.read_text() == '{}'
        finally:
            snapshot_path.write_text(valid_snapshot)
        print('PASS: credenziali, regole, sessioni, UDP, TCP concorrente, JAR, riavvio regolare e forzato, snapshot corrotti e fine dataset')
    finally:
        for c in clients: c.close()
        if server.poll() is None: stop(server)

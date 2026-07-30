import signal
import threading

from django.conf import settings
from django.core.management.base import BaseCommand

from kiosk.video_processing import process_one_job, recover_stale_jobs, transcoding_availability


class Command(BaseCommand):
    help = "Procesează coada persistentă de optimizare video, câte un singur job simultan."

    def add_arguments(self, parser):
        parser.add_argument("--watch", action="store_true", help="Rulează continuu și urmărește coada.")
        parser.add_argument(
            "--sleep",
            type=float,
            default=settings.VIDEO_QUEUE_SLEEP_SECONDS,
            help="Secunde între verificările cozii când nu există joburi.",
        )

    def handle(self, *args, **options):
        stop_event = threading.Event()

        def request_stop(signum, frame):
            stop_event.set()

        previous_handlers = {
            signal.SIGTERM: signal.signal(signal.SIGTERM, request_stop),
            signal.SIGINT: signal.signal(signal.SIGINT, request_stop),
        }
        try:
            recovered = recover_stale_jobs()
            if recovered:
                self.stdout.write(f"Au fost recuperate {recovered} joburi întrerupte.")
            available, error = transcoding_availability()
            if not available:
                self.stderr.write(self.style.ERROR(error))
                return
            self.stdout.write(self.style.SUCCESS("Workerul de optimizare video este pregătit."))
            while not stop_event.is_set():
                processed = process_one_job(should_stop=stop_event.is_set)
                if not options["watch"]:
                    break
                if not processed:
                    stop_event.wait(max(0.1, options["sleep"]))
        finally:
            for signum, handler in previous_handlers.items():
                signal.signal(signum, handler)
            self.stdout.write("Workerul de optimizare video s-a oprit.")
